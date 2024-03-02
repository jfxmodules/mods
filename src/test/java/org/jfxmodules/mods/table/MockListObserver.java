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

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.function.BiPredicate;

import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A mock observer that tracks calls to its onChanged() method,
 * combined with utility methods to make assertions on the calls made.
 *
 */
public class MockListObserver<E> implements ListChangeListener<E> {
    private boolean tooManyCalls;
    CountDownLatch expectedCalls;
    private boolean threaded = false;
    
    public MockListObserver() {
        
    }
    
    public MockListObserver(int expectedCallCount) {
        expectedCalls = new CountDownLatch(expectedCallCount);
        threaded = true;
    }

    static class Call<E> {
        ObservableList<? extends E> list;
        List<? extends E> removed;
        int from;
        int to;
        private int[] permutation;
        private boolean update;
        @Override
        public String toString() {
            return  "removed: " + removed + ", from: " + from + ", to: " + to + ", permutation: " + Arrays.toString(permutation);
        }
    }

    List<Call<E>> calls = new LinkedList<>();

    @Override
    public void onChanged(Change<? extends E> change) {
        if (calls.isEmpty()) {
            while (change.next()) {
                Call<E> call = new Call<>();
                call.list = change.getList();
                call.removed = change.getRemoved();
                call.from = change.getFrom();
                call.to = change.getTo();
                if (change.wasPermutated()) {
                    call.permutation = new int[call.to - call.from];
                    for (int i = 0; i < call.permutation.length; ++i) {
                        call.permutation[i] = change.getPermutation(i + call.from);
                    }
                } else {
                    call.permutation = new int[0];
                }
                call.update = change.wasUpdated();
                calls.add(call);

                // Check generic change assertions
                assertFalse(change.wasPermutated() && change.wasUpdated());
                assertFalse((change.wasAdded() || change.wasRemoved()) && change.wasUpdated());
                assertFalse((change.wasAdded() || change.wasRemoved()) && change.wasPermutated());
                if (threaded) {
                    if (change.wasAdded() || change.wasPermutated()) {
                        System.out.println("Countingdown latch.");
                        expectedCalls.countDown();
                    }
                }
            }
        } else {
            tooManyCalls = true;
            if (threaded) {
                expectedCalls.countDown();
            }
        }
    }

    public void check0() {
        assertEquals(0, calls.size());
    }

    public void check1AddRemove(ObservableList<E> list,
                       List<E> removed,
                       int from,
                       int to) {
        checkN(1);
        checkAddRemove(0, list, removed, from, to);
    }

    public void checkAddRemove(int idx, ObservableList<E> list,
                       List<E> removed,
                       int from,
                       int to) {
        checkAddRemove(idx, list, removed, Objects::equals, from, to);
    }

    public void checkAddRemove(int idx, ObservableList<E> list,
                               List<E> removed,
                               BiPredicate<E, E> equalityComparer,
                               int from,
                               int to) {
        if (removed == null) {
            removed = Collections.emptyList();
        }
        assertFalse(tooManyCalls);
        Call<E> call = calls.get(idx);
        assertSame(list, call.list);
        assertEquals(removed.size(), call.removed.size());
        for (int i = 0; i < removed.size(); ++i) {
            assertTrue(equalityComparer.test(removed.get(i), call.removed.get(i)));
        }
        assertEquals(from, call.from);
        assertEquals(to, call.to);
        assertEquals(0, call.permutation.length);
    }

    public void check1Permutation(ObservableList<E> list, int[] perm) {
        checkN(1);
        checkPermutation(0, list, 0, list.size(), perm);
    }

    public void check1Permutation(ObservableList<E> list, int from, int to, int[] perm) {
        checkN(1);
        checkPermutation(0, list, from, to, perm);
    }

    public void checkPermutation(int idx, ObservableList<E> list, int from, int to, int[] perm) {
        assertFalse(tooManyCalls);
        Call<E> call = calls.get(idx);
        assertEquals(list, call.list);
        assertEquals(Collections.EMPTY_LIST, call.removed);
        assertEquals(from, call.from);
        assertEquals(to, call.to);
        assertArrayEquals(perm, call.permutation);
    }

    public void check1Update(ObservableList<E> list, int from, int to) {
        checkN(1);
        checkUpdate(0, list, from, to);
    }

    public void checkUpdate(int idx, ObservableList<E> list, int from, int to) {
        assertFalse(tooManyCalls);
        Call<E> call = calls.get(idx);
        assertEquals(list, call.list);
        assertEquals(Collections.EMPTY_LIST, call.removed);
        assertArrayEquals(new int[0], call.permutation);
        assertEquals(true, call.update);
        assertEquals(from, call.from);
        assertEquals(to, call.to);
    }

    public void check1() {
        checkN(1);
    }

    public void checkN(int n) {
        assertFalse(tooManyCalls);
        assertEquals(n, calls.size());
    }

    public void clear() {
        calls.clear();
        tooManyCalls = false;
    }
}
