/*
 * Copyright 2024 Chad Preisler.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jfxmodules.mods.table;

import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import org.jfxmodules.mods.table.internals.ThreadSafeChange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;

public class ThreadedSortedListTest {
    static final Logger LOGGER = Logger.getLogger(ThreadedSortedListTest.class.getName()); 
    private ObservableList<String> list;
    private MockListObserver<String> mockListObserver;
    private volatile SortedList<String> sortedList;
    
    @BeforeAll
    static void initJfxRuntime() {
        Platform.startup(() -> {});
        Logger rootLogger = LogManager.getLogManager().getLogger("");
        rootLogger.setLevel(Level.FINE);
        for (Handler h : rootLogger.getHandlers()) {
            h.setLevel(Level.FINE);
        }
    }
    
    @BeforeEach
    public void setUp() {
        list = FXCollections.observableArrayList();
        list.addAll("a", "c", "d", "c");
        sortedList = new SortedList(list, Comparator.naturalOrder(), true);
    }
    
    @Test
    public void testAddWhenUnsorted() throws InterruptedException {
        mockListObserver = new MockListObserver<>(3);
        sortedList.addListener(mockListObserver);    
        sortedList.setComparator(null);
        list.add(2, "b");
        mockListObserver.expectedCalls.await(2, TimeUnit.SECONDS);
        assertEquals(5, sortedList.size());
        assertEquals(Arrays.asList("a", "c", "b", "d", "c"), sortedList);
    }
    
    @Test
    public void testMultipleBulkAddsRemoves() throws InterruptedException {
        System.out.println("Calling clear on list.");
        list.clear();
        //System.err.println("List cleared");
        var callCount = new AtomicInteger(0);
        CountDownLatch counter = new CountDownLatch(20);
        sortedList.addListener((ListChangeListener)(c)-> {
            callCount.incrementAndGet();
            LOGGER.info("Size of list: " + list.size());
            if (list.size() > 0) {
                if (list.size() != 6000) {
                    //zero out the latch to end test.
                    var latchCount = counter.getCount();
                    for (int i = 0; i <= latchCount; i++) {
                        counter.countDown();
                    }
                    assertEquals(6000, list.size());
                }
            }
            counter.countDown();
        });
        new Thread(()-> {
            for (int i = 0; i < 20; i++) {
                var aList = new ArrayList<String>();
                for (int t = 0; t < 2000; t++) {
                    aList.add(String.valueOf(t));
                }
                Platform.runLater(()-> {
                    if (list.size() + aList.size() > 6000) {
                        LOGGER.info("Removing items from list");
                        list.remove(list.size() - aList.size(), list.size());
                    }
                    LOGGER.info("Adding items to the list");
                    list.addAll(aList);
                });
            } 
        }).start();
       var countdown2 = new CountDownLatch(1);
        new Thread(()-> {
            try {
                counter.await(10, TimeUnit.SECONDS);
                assertEquals(20, callCount.get());
            } catch (InterruptedException ex) {
                //do nothing
            } finally {
                countdown2.countDown();                
            }
        }).start();
        countdown2.await(2, TimeUnit.SECONDS);
    }
    
    @Test
    public void testMultipleBulkAddsWithFilter() throws InterruptedException {
        System.out.println("Calling clear on list.");
        var callCount = new AtomicInteger(0);
        CountDownLatch counter = new CountDownLatch(8);
        list = FXCollections.observableArrayList();
        sortedList = new SortedList(list, Comparator.naturalOrder(), true);
        sortedList.addListener((ListChangeListener)(c)-> {
            while(c.next()) {
                System.out.println(String.format("Change from: %s to %s", c.getFrom(), c.getTo()));
                callCount.incrementAndGet();
                counter.countDown();
            }
        });
        
        sortedList.setFilter((value)->{
            return "2".equals(value) || "6".equals(value);
        });
        
        new Thread(()-> {
            for (int i = 0; i < 4; i++) {
                var aList = new ArrayList<String>();
                for (int t = 0; t <= 10; t++) {
                    aList.add(String.valueOf(t));
                }
                var latch = new CountDownLatch(1);
                Platform.runLater(()-> {
                    LOGGER.info("Adding items to the list");
                    list.addAll(aList);
                    latch.countDown();
                });
                try {
                    latch.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    //ignoring
                }
            }
        }).start();
        var countdown2 = new CountDownLatch(1);
        new Thread(()-> {
            try {
                counter.await();                
            } catch (InterruptedException ex) {
                //do nothing
            } finally {
                countdown2.countDown();                
            }
        }).start();
        countdown2.await(10, TimeUnit.SECONDS);
        assertEquals(8, callCount.get());
        assertEquals(8, sortedList.size());
        assertEquals(Arrays.asList("2", "2", "2", "2"), sortedList.subList(0, 4));
        assertEquals(Arrays.asList("6", "6", "6", "6"), sortedList.subList(4, sortedList.size()));
    }    

    @Test
    public void testLatestFilterRequestWins() throws InterruptedException {
        var applied = new CountDownLatch(1);
        sortedList.addListener((ListChangeListener<String>) change -> {
            while (change.next()) {
                if (sortedList.size() == 1 && "d".equals(sortedList.get(0))) {
                    applied.countDown();
                }
            }
        });

        sortedList.setFilter(value -> "a".equals(value));
        sortedList.setFilter(value -> "c".equals(value));
        sortedList.setFilter(value -> "d".equals(value));

        assertTrue(applied.await(2, TimeUnit.SECONDS));
        assertEquals(List.of("d"), sortedList);
    }

    @Test
    public void testCachedSortKeyRefreshesForAddedAndUpdatedRecords() throws InterruptedException {
        var keyCalls = new AtomicInteger();
        var sorted = new SortedList<String>(list, true);
        var initialSort = new CountDownLatch(1);
        var added = new CountDownLatch(1);
        var updated = new CountDownLatch(1);
        var notifications = new AtomicInteger();
        sorted.addListener((ListChangeListener<String>) change -> {
            switch (notifications.incrementAndGet()) {
                case 1 -> initialSort.countDown();
                case 2 -> added.countDown();
                case 3 -> updated.countDown();
                default -> { }
            }
        });

        sorted.setSortKey(value -> {
            keyCalls.incrementAndGet();
            return value;
        });
        assertTrue(initialSort.await(2, TimeUnit.SECONDS));
        assertEquals(List.of("a", "c", "c", "d"), sorted);
        assertEquals(4, keyCalls.get());

        list.add("b");
        assertTrue(added.await(2, TimeUnit.SECONDS));
        assertEquals(List.of("a", "b", "c", "c", "d"), sorted);
        assertEquals(5, keyCalls.get());

        list.set(0, "e");
        assertTrue(updated.await(2, TimeUnit.SECONDS));
        assertEquals(List.of("b", "c", "c", "d", "e"), sorted);
        assertEquals(6, keyCalls.get());
    }
    
    @Test
    public void testThreadSafeChange() {
        var change = new TestChange<String>(list);
        var threadSafe = ThreadSafeChange.create(change);
        threadSafe.next();
        assertTrue(change.next());
    }

}
