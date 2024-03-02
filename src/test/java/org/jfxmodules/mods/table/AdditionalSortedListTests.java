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
import java.util.Collection;
import java.util.Comparator;
import java.util.function.Predicate;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 *
 * @author Chad Preisler
 */
public class AdditionalSortedListTests {
    private ObservableList<String> list;
    private SortedList<String> sortedList;
    private MockListObserver<String> mockListObserver;
    private Collection<String> initialList = Arrays.asList("a", "c", "d", "c");

    @BeforeEach
    public void setUp() {
        list = FXCollections.observableArrayList();
        list.addAll(initialList);
        mockListObserver = new MockListObserver<>();
        sortedList = new SortedList<>(list, Comparator.naturalOrder());
        sortedList.addListener(mockListObserver);
    }
    
    @Test
    public void testInitLists () {
        assertEquals(4, sortedList.unsorted.size());
        assertEquals(4, sortedList.sorted.size());
        assertEquals(4, sortedList.sortedById.size());
        assertEquals(4, sortedList.unsortedById.size());
    }
    
    @Test
    public void testFilterSize() {
        sortedList.setFilter((value)-> !value.equals("c"));
        list.add("c");
        assertEquals(2, sortedList.size());
        assertEquals(2, sortedList.filteredList.size());
        assertEquals(5, sortedList.unsorted.size());
        assertEquals(5, sortedList.sorted.size());
        assertEquals(5, sortedList.sortedById.size());
        assertEquals(5, sortedList.unsortedById.size());
    }
    
    @Test
    public void testFilterAddRecord() {
        sortedList.setFilter((value)-> !value.equals("c"));
        list.add("d");
        assertEquals(3, sortedList.size());
        assertEquals(3, sortedList.filteredList.size());
        assertEquals(5, sortedList.unsorted.size());
        assertEquals(5, sortedList.sorted.size());
        assertEquals(5, sortedList.sortedById.size());
        assertEquals(5, sortedList.unsortedById.size());
    }
    
    @Test
    public void testFilterRemoveRecord() {
        sortedList.setFilter((value)-> !value.equals("c"));
        list.remove("c");
        assertEquals(2, sortedList.size());
        assertEquals(2, sortedList.filteredList.size());
        assertEquals(3, sortedList.unsorted.size());
        assertEquals(3, sortedList.sorted.size());
        assertEquals(3, sortedList.sortedById.size());
        assertEquals(3, sortedList.unsortedById.size());
    }
    
    @Test
    public void testFilterRemoveAllRecord() {
        sortedList.setFilter((value)-> !value.equals("c"));
        list.removeAll("c");
        assertEquals(2, sortedList.size());
        assertEquals(2, sortedList.filteredList.size());
        assertEquals(2, sortedList.unsorted.size());
        assertEquals(2, sortedList.sorted.size());
        assertEquals(2, sortedList.sortedById.size());
        assertEquals(2, sortedList.unsortedById.size());
    }
    
    @Test
    public void testFilterRemoveAddRecord() {
        sortedList.setFilter((value)-> !value.equals("c"));
        list.removeAll("c");
        list.add("e");
        list.add("c");
        assertEquals(3, sortedList.size());
        assertEquals(3, sortedList.filteredList.size());
        assertEquals(4, sortedList.unsorted.size());
        assertEquals(4, sortedList.sorted.size());
        assertEquals(4, sortedList.sortedById.size());
        assertEquals(4, sortedList.unsortedById.size());
        
        var unSortedValues = Arrays.asList(sortedList.unsorted.get(0).element(),
                sortedList.unsorted.get(1).element(),
                sortedList.unsorted.get(2).element(),
                sortedList.unsorted.get(3).element());
        assertEquals(Arrays.asList("a", "d", "e", "c"), unSortedValues);
        
        var sortedValues = Arrays.asList(sortedList.sorted.get(0).element(),
                sortedList.sorted.get(1).element(),
                sortedList.sorted.get(2).element(),
                sortedList.sorted.get(3).element());
        assertEquals(Arrays.asList("a", "c", "d", "e"), sortedValues);
        
        var filteredValues = Arrays.asList(sortedList.filteredList.filtered().get(0).element(),
                sortedList.filteredList.filtered().get(1).element(),
                sortedList.filteredList.filtered().get(2).element());
        assertEquals(Arrays.asList("a", "d", "e"), filteredValues);
    }
    
    @Test
    public void changeFilter() {
        sortedList.setFilter((value)-> value.equals("c"));
        assertEquals(Arrays.asList("c", "c"), sortedList);
        list.add("c");
        assertEquals(Arrays.asList("c", "c", "c"), sortedList);
        list.add("b");
        list.add("e");
        assertEquals(Arrays.asList("c", "c", "c"), sortedList);
        mockListObserver.clear();
        sortedList.setFilter((value)->true);
        assertEquals(Arrays.asList("a", "b", "c", "c", "c", "d", "e"), sortedList);
        mockListObserver.check1AddRemove(sortedList, Arrays.asList("c", "c", "c"), 0, 7);
    }
}
