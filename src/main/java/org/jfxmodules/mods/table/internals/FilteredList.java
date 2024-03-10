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
package org.jfxmodules.mods.table.internals;

import static java.nio.file.Files.size;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * This class is designed to be used with the visualkafka custom SortedList
 * class. You should not use this class directly. Instead call SortedList.setFilter.
 * @author Chad Preisler
 * @param <E>
 */
public class FilteredList<E> {
    public Predicate<E> predicate = (t)-> true;
    final ArrayList<Element<E>> filtered;
    final ArrayList<IdAndIndex> filteredById; 
    
    final ArrayList<Element<E>> lastFiltered = new ArrayList<>();
    final ArrayList<Element<E>> add = new ArrayList<>();
    final ArrayList<Element<E>> remove = new ArrayList<>();
    final ArrayList<Element<E>> update = new ArrayList<>();
    protected final ArrayList<Integer> addedIndexes = new ArrayList<>();
    protected final ArrayList<IndexAndElement<E>> removedIndexes = new ArrayList<>();
    protected final ArrayList<Integer> updatedIndexes = new ArrayList<>();
    
    private final Comparator<IdAndIndex> idComparator = (e1, e2)->Long.compare(e1.id(), e2.id());
    
    protected static record IndexAndElement<E>(int index, E element) {
    }
    
    public FilteredList() {
        filtered = new ArrayList<>();
        filteredById = new ArrayList<>();
    }
    
    public FilteredList(ArrayList<Element<E>> filtered, 
                        ArrayList<IdAndIndex> filteredById,
                        Predicate<E> predicate) {
        this.filtered = new ArrayList<>(filtered);
        this.filteredById = new ArrayList<>(filteredById);
        this.predicate = predicate;
    }
    
    /**
     * Set the predicate to filter the records.
     * @param predicate 
     */
    public void setPredicate(Predicate predicate) {
        if (predicate == null) {
            this.predicate = t -> true;
        } else {
            this.predicate = predicate;
        }
    }
    
    public ArrayList<Element<E>> filtered() {
        return filtered;
    }
    
    public ArrayList<IdAndIndex> filteredById() {
        return filteredById;
    }
    
    public ArrayList<Element<E>> lastFiltered() {
        return lastFiltered;
    }
    
    /**
     * Load this lists data from the source and filter if appropriate.
     * @param source
     * @param elementComparator 
     */
    public void load(List<Element<E>> source, Comparator<Element<E>> elementComparator) {
        reset();
        for (int i = 0; i < source.size(); i++) {
            var e = source.get(i);
            if (predicate.test(e.element())) {
                filtered.add(e);
            }
        }
        for (int i = 0; i < filtered.size(); i++) {
            filteredById.add(new IdAndIndex(filtered.get(i).id(), i));
        }
        filteredById.sort(idComparator);
    }
    
    /**
     * Sort the list according to the comparator sent in.
     * @param elementComparator 
     */
    public void sort(Comparator<Element<E>> elementComparator) {
        if (elementComparator != null) {
            filtered.sort(elementComparator);
            for (int i = 0; i < filtered.size(); i++) {
                filteredById.add(new IdAndIndex(filtered.get(i).id(), i));
            }
        }
        filteredById.sort(idComparator);
    }
    
    /**
     * Reset calling nextRemove for each removed item passing the index and
     * element removed.
     * @param nextRemove 
     */
    public void reset(BiConsumer<Integer, E> nextRemove) {
        var removeList = new ArrayList<Element<E>>();
        removeList.addAll(filtered);
        for (int i = removeList.size() - 1; i >= 0; i--) {
            nextRemove.accept(i, removeList.get(i).element());
        }
        reset();
    }
    
    /**
     * Clear the state of this filtered list.
     */
    public void reset() {
        filtered.clear();
        filteredById.clear();
        clearUpdate();
    }  
    
    public List<E> getElements() {
        var removed = new ArrayList<E>(filtered.size());
        filtered.forEach(e -> removed.add(e.element()));
        return removed;
    }
    
    public int size() {
        return filtered.size();
    }
    
    /**
     * Call this when applying updates to a record. Depending on how the element
     * was changed, it may be an add, update, or delete for the filtered list.
     * @param elementWithId Element that was updated.
     */
    public void updateChangedElement(Element<E> elementWithId) {
        var found = Collections.binarySearch(filteredById, new IdAndIndex(elementWithId.id(), 0), idComparator);
        if (predicate.test(elementWithId.element())) {
            if (found < 0) {
                add.add(elementWithId);                
            } else {
                update.add(elementWithId);
            }   
        } else {
            if (found >= 0) {
                remove.add(elementWithId);
            }
        }
    }
    
    /**
     * Call this after updateChangedElement to do any add, remove, and updates.
     * @param elementComparator Optional comparator used to sort the list.
     */
    public void applyUpdates(Comparator<Element<E>> elementComparator) {
        if (!remove.isEmpty()) {
            Collections.sort(remove, (e1, e2)->Long.compare(e2.id(), e1.id()));
            remove.forEach(element -> {
                var searchResult = Collections.binarySearch(filteredById, new IdAndIndex(element.id(), 0), idComparator); 
                var index = filteredById.get(searchResult).index();
                filtered.remove(index);
                filteredById.remove(searchResult);
                removedIndexes.add(new IndexAndElement<E>(index, element.element()));
            });
            updateIndexes();
        }

        if (elementComparator != null) {
            add.stream().forEach(e -> {
                var sortedIndex =  Collections.binarySearch(filtered, e, elementComparator);
                filtered.add(sortedIndex, e);
                updateIndexes();
                addedIndexes.add(sortedIndex);
            });
            lastFiltered.addAll(filtered);
            update.forEach(elementWithId -> {
                var sortedIndex = Collections.binarySearch(filtered, elementWithId, elementComparator); 
                updatedIndexes.add(sortedIndex);
            });
        } else {
            add.stream().forEach(e -> {
                var byIdIndex =  Collections.binarySearch(filteredById, new IdAndIndex(e.id(), 0), idComparator);
                if (byIdIndex < 0) {
                    byIdIndex = ~byIdIndex;
                }
                if (byIdIndex >= filteredById.size()) {
                    filtered.add(e);
                    addedIndexes.add(filtered.size() - 1);
                } else {
                    var index = filteredById.get(byIdIndex).index();
                    filtered.add(index, e);
                    addedIndexes.add(index);
                }
                updateIndexes();
            });
            
            update.forEach(elementWithId -> {
                var byIdIndex =  Collections.binarySearch(filteredById, new IdAndIndex(elementWithId.id(), 0), idComparator);
                updatedIndexes.add(filteredById.get(byIdIndex).index());
            });            
        }
    }
    
    private void updateIndexes() {
        filteredById.clear();
        filteredById.ensureCapacity(filtered.size());
        for (int i = 0; i < filtered.size(); i++) {
            filteredById.add(new IdAndIndex(filtered.get(i).id(), i));
        }
        filteredById.sort(idComparator);
    }
    
    /**
     * After an update notify other table transformations of udpate, deletes, 
     * and adds.
     * @param nextUpdate Consumer to call when record updated. Gets index updated.
     * @param nextRemove Consumer to call when record removed. Gets index and element.
     * @param nextAdd Consumer to call when record added. Gets index of added.
     */
    public void fireNotifications(Consumer<Integer> nextUpdate, 
                        BiConsumer<Integer, E> nextRemove, 
                        BiConsumer<Integer, Integer> nextAdd) { 
        removedIndexes.forEach(indexAndElement -> {
            nextRemove.accept(indexAndElement.index, indexAndElement.element);
        });
        addedIndexes.forEach(index -> {
            nextAdd.accept(index, index + 1);
        });
        updatedIndexes.forEach(index -> {
            nextUpdate.accept(index);
        });
    }
    
    /**
     * Clean up state of update.
     */
    public void clearUpdate() {
        add.clear();
        remove.clear();
        update.clear();
        addedIndexes.clear();
        removedIndexes.clear();
        updatedIndexes.clear();
    }
    
    /**
     * Remove the elements passed in if they exist in the filtered list.
     * @param elementsToRemove
     * @return List of indexes removed.
     */
    public List<Element<E>> doRemove(List<Element<E>> elementsToRemove) {
        var removeIndexes = new ArrayList<Element<E>>();
        for (int i = 0; i < elementsToRemove.size(); i++) {
            var e = elementsToRemove.get(i);
            var searchResult = Collections.binarySearch(filteredById, new IdAndIndex(e.id(), 0), idComparator);
            if (searchResult >= 0) {
                removeIndexes.add(new Element<>(filteredById.get(searchResult).index(), e.element()));
            }
        }
        Collections.sort(removeIndexes, (e1, e2)-> Long.compare(e2.id(), e1.id()));
        removeIndexes.forEach(index -> {
            filtered.remove((int)index.id());                    
        });
        return removeIndexes;
    }
    
    /**
     * If the element comparator is not null, add the element to the filtered list in sorted order.
     * The element is not inserted into the list if the element comparator is null.
     * 
     * @param from
     * @param element
     * @param elementComparator
     * @return The position the element was inserted at, or -1 if no insert was done.
     */
    public int doSortedAdd(Element<E> element, Comparator<Element<E>> elementComparator) {
        if (elementComparator != null && predicate.test(element.element())) {
            var pos = Collections.binarySearch(filtered, element, elementComparator);
            if (pos < 0) {
                pos = ~pos;
            }
            filtered.add(pos, element);
            return pos;
        }
        return -1;
    }
    
    /**
     * Add all elements that match the predicate.
     * @param add List of items to add.
     * @param from The beginning index to insert the item at.
     */
    public void doAdd(List<Element <E>> add, int from) {
        boolean append = from > filtered.size();
        int index = from;
        for (int i = 0; i < add.size(); i++) {
            var element = add.get(i);
            if (predicate.test(element.element())) {
                if (!append) {
                    var indexAndId = new IdAndIndex(element.id(), filtered.size());
                    filtered.add(index, element);                    
                    filteredById.add(indexAndId);                
                    index++;
                } else {
                    var indexAndId = new IdAndIndex(element.id(), i);
                    filteredById.add(indexAndId);                
                    filtered.add(element);
                }
            }
        }
    }
    
}
