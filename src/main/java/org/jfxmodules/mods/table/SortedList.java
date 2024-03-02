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

import javafx.collections.transformation.TransformationList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import javafx.application.Platform;

import javafx.beans.NamedArg;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ObjectPropertyBase;
import javafx.collections.ListChangeListener.Change;
import javafx.collections.ObservableList;
import javafx.scene.control.TableView;
import javafx.util.Callback;
import org.jfxmodules.mods.table.internals.Element;
import org.jfxmodules.mods.table.internals.FilteredList;
import org.jfxmodules.mods.table.internals.IdAndIndex;
import org.jfxmodules.mods.table.internals.SimplePermutationChange;
import org.jfxmodules.mods.table.internals.SourceAdapterChange;
import org.jfxmodules.mods.table.internals.ThreadSafeChange;

/**
 * Wraps an ObservableList and sorts its content.All changes in the
 * ObservableList are propagated immediately to the SortedList. This class is loosely based on the original SortedList in JavaFX. The
 * main goal was to decrease the time to load large batches of records. It
 * also combines sorted list and filtered list functionality. The goal with filtered
 * list is to reduce the time taken to remove the filter from the list.
 * 
 * To increase performance this class maintains three versions of the list.
 * It keeps the sorted, unsorted, and filtered versions of the list. Because
 * these lists all point to the same objects the memory foot print is still 
 * manageable. 
 * 
 * This implementation gives each element a number that serves as a unique ID.
 * That ID is used to make searching for items in the other lists perform better.
 * 
 * This implementation achieves some of its performance improvements by using threads.
 * Threads are disabled by default. To turn them on use the appropriate constructor.
 * 
 *
 * NOTE: When in thread mode the predicate is NOT run on the main JavaFX thread!
 *       When threaded you should not do updates to UI components in the predicate
 *       without calling Platform.runLater.
 * 
 * Currently this implementation of SortedList passes the original JavaFX
 * tests in SortedListTest and FilteredListTest (except one test that relied on an internal package).
 * The unit test class is included as part of this project.
 *
 * @param <E>
 * @see TransformationList
 */
public final class SortedList<E> extends TransformationList<E, E> {
    static final Logger LOGGER = Logger.getLogger(SortedList.class.getName()); 
    
    /**
     * This is taken from the original SortedList in JavaFX.
     */
    public final Callback<TableView<E>, Boolean> DEFAULT_SORT_POLICY = new Callback<>() {
        @Override
        public Boolean call(TableView table) {
            try {
                ObservableList<?> itemsList = table.getItems();
                if (itemsList instanceof SortedList sortedList) {
                    // it is the responsibility of the SortedList to bind to the
                    // comparator provided by the TableView. However, we don't
                    // want to fail the sort (which would put the UI in an
                    // inconsistent state), so we return true here, but only if
                    // the SortedList has its comparator bound to the TableView
                    // comparator property.
                                        boolean comparatorsBound = sortedList.comparatorProperty().
                            isEqualTo(table.comparatorProperty()).get();

                    if (!comparatorsBound) {
                        // this isn't a good situation to be in, so lets log it
                        // out in case the developer is unaware
                        String s = "TableView items list is a SortedList, but the SortedList "
                                + "comparator should be bound to the TableView comparator for "
                                + "sorting to be enabled (e.g. "
                                + "sortedList.comparatorProperty().bind(tableView.comparatorProperty());).";
                        Logger.getLogger(SortedList.class.getName()).info(s);
                    }
                    return comparatorsBound;
                } else {
                    if (itemsList == null || itemsList.isEmpty()) {
                        // sorting is not supported on null or empty lists
                        return true;
                    }

                    Comparator comparator = table.getComparator();
                    if (comparator == null) {
                        return true;
                    }

                    // otherwise we attempt to do a manual sort, and if successful
                    // we return true
                    sorted.sort(comparator);
                    return true;
                }
            } catch (UnsupportedOperationException e) {
                // TODO might need to support other exception types including:
                // ClassCastException - if the class of the specified element prevents it from being added to this list
                // NullPointerException - if the specified element is null and this list does not permit null elements
                // IllegalArgumentException - if some property of this element prevents it from being added to this list

                // If we are here the list does not support sorting, so we gracefully
                // fail the sort request and ensure the UI is put back to its previous
                // state. This is handled in the code that calls the sort policy.
                return false;
            }
        }
    };

    final ArrayList<Element<E>> sorted;
    final ArrayList<IdAndIndex> sortedById;
    final ArrayList<Element<E>> unsorted;
    final ArrayList<IdAndIndex> unsortedById;
    FilteredList<E> filteredList;
    
    private Comparator<Element<E>> elementComparator;
    private final Comparator<IdAndIndex> idComparator = (e1, e2)->Long.compare(e1.id(), e2.id());
    private final boolean threaded;
    private final ExecutorService es = Executors.newSingleThreadExecutor();
    private boolean applyFilter;    
    private long lastId = 0;    
    private final AtomicInteger size = new AtomicInteger(0);

    protected static record IndexAndElement<E>(int index, E element) {
    }
    
    /**
     * Creates a new SortedList wrapped around the source list.The source list
 will be sorted using the comparator provided. If null is provided, the
 list stays unordered and is equal to the source list.
     *
     * @param source a list to wrap
     * @param comparator a comparator to use or null for unordered List
     * @param threaded if true operations (like indexing) are done on a background thread.
     */
    @SuppressWarnings("unchecked")
    public SortedList(@NamedArg("source") ObservableList<? extends E> source, @NamedArg("comparator") Comparator<? super E> comparator, boolean threaded) {
        super(source);
        this.size.set(source.size());
        this.threaded = threaded;
        sorted = new ArrayList<>(source.size());
        sortedById = new ArrayList<>(source.size());
        unsorted = new ArrayList<>(source.size());
        unsortedById = new ArrayList<>();
        setComparator(comparator);
        loadElements(source);
        maybeSortAndReIndex(sorted, sortedById);
        filteredList = new FilteredList<>();
    }
    
    /**
     * If an elementComparator is set, sort the list using that comparator
     * and build the listById. The listById is cleared before building the index.
     * @param list The list to be sorted.
     * @param listById The list to hold the IdAndIndex objects.
     */
    private void maybeSortAndReIndex(List<Element<E>> list, ArrayList<IdAndIndex> listById) {
        if (elementComparator != null) {
            list.sort(elementComparator);
            listById.clear();
            for (int i = 0; i < list.size(); ++i) {
                listById.add(new IdAndIndex(list.get(i).id(), i));                
            }
            listById.sort(idComparator);
        }
    }
    
    /**
     * Defaults list to single threaded mode.
     * @param source a list to wrap
     * @param comparator a comparator to use or null for unordered List
     */
    public SortedList(@NamedArg("source") ObservableList<? extends E> source, @NamedArg("comparator") Comparator<? super E> comparator) {
        this(source, comparator, false);
    }

    /**
     * Constructs a new unordered SortedList wrapper around the source list.
     * Creates single threaded and null comparator.
     *
     * @param source the source list
     * @see #SortedList(javafx.collections.ObservableList, java.util.Comparator)
     */
    public SortedList(@NamedArg("source") ObservableList<? extends E> source) {
        this(source, (Comparator) null, false);
    }
    
    /**
     * Constructs a new unordered SortedList wrapper around the source list.Uses threads according to the threaded argument.
     *
     * @param source the source list
     * @param threaded if true, will use threads for intensive list operations.
     * @see #SortedList(javafx.collections.ObservableList, java.util.Comparator)
     */
    public SortedList(@NamedArg("source") ObservableList<? extends E> source, boolean threaded) {
        this(source, (Comparator) null, threaded);
    }

    private void loadElements(ObservableList<? extends E> source) {
        for (int i = 0; i < source.size(); ++i) {
            var e = new Element<>(lastId++, source.get(i));
            var idAndIndex = new IdAndIndex(e.id(), i);
            unsorted.add(e);
            unsortedById.add(idAndIndex);
            if (comparator != null) {
                sorted.add(e);
            }
        }
        unsortedById.sort(idComparator);
    }
    
    /**
     * Called by JavaFX anytime a change is made to the source list.
     * @param c 
     */
    @Override
    protected void sourceChanged(Change<? extends E> c) {
        var processedChanges = elementComparator != null || applyFilter;
        if (threaded) {
            LOGGER.info("Submitting to executor service.");
            var safeChange = ThreadSafeChange.create(c);
            es.submit(() -> {
                LOGGER.fine("Starting task in exector service.");
                try {
                    var notificationChanges = doChanges(safeChange);                    
                    size.set(unsorted.size());
                    LOGGER.fine("Submitting change to UI thread.");
                    var uiLatch = new CountDownLatch(1);
                    Platform.runLater(()-> {
                        LOGGER.fine("Starting update to UI thread");
                        notifyChanges(processedChanges, notificationChanges, c);
                        LOGGER.fine("Finished update to UI thread");
                        uiLatch.countDown();
                    });
                    uiLatch.await();
                } catch(Throwable t) {
                    LOGGER.log(Level.SEVERE, "Error processing changes for sorted list.", t);
                }
                LOGGER.fine("Finished task in exector service.");
            });
        } else {
            LOGGER.info("Starting change for single threaded list.");
            var notificationChanges = doChanges(c);
            size.set(unsorted.size());
            notifyChanges(processedChanges, notificationChanges, c);
            LOGGER.info("Finished change for single threaded list.");
        }
    }
    
    /**
     * Common method to iterate the changes.
     * @param c
     * @return List of methods to run that will notify the main thread of
     * changes made.
     */
    private List<Runnable> doChanges(Change<? extends E> c) {
        var updateNotifications = new ArrayList<Runnable>();
        LOGGER.info("Starting changes.");
        while (c.next()) {
           var updateNotification = update(c);
           if (updateNotification != null) {
               updateNotifications.add(updateNotification);
           }
        }
        LOGGER.info("Finished changes.");
        return updateNotifications;
    }
    
    /**
     * The only time we process notifyChanges is when there is a change to 
     * the the contents of the this SortedList. So an add or remove when the list is sorted
     * or filtered results in the contents changing. If this SotedList is not
     * currently sorted (no comparator) or not currently filtered (applyFilter is false)
     * then there are no changes to this SortedList. We just notify any downstream
     * listeners that a change has happened.
     * 
     * @param processChanges Flag that tells us if the list was sorted/filtered at the time the changes were done.
     * @param notifyChanges A list of functions to call to notify the changes to this list.
     * @param c The original change that triggered the change to this SortedList
     */
    private void notifyChanges(boolean processChanges, List<Runnable> notifyChanges, Change<? extends E> c) {
        LOGGER.log(Level.INFO, "Starting changes on main thread...{0}", size());
        if (processChanges) {
            // process the add/removes from the contents of this SortedList
            beginChange();
            notifyChanges.forEach(update -> update.run());
            endChange();           
        } else {
            // no changes done to this list, just pass the change downstream
           fireChange(new SourceAdapterChange<>(this, c));
        }
        LOGGER.info("Finished changes on main thread...");
    }

    /**
     * The comparator that denotes the order of this SortedList.
     * Null for unordered SortedList.
     */
    private ObjectProperty<Comparator<? super E>> comparator;

    public final ObjectProperty<Comparator<? super E>> comparatorProperty() {
        if (comparator == null) {
            comparator = new ObjectPropertyBase<Comparator<? super E>>() {

                @Override
                protected void invalidated() {
                    LOGGER.log(Level.FINE, "Start invalidating list.");
                    Comparator<? super E> current = get();
                    if (threaded) {
                        es.submit(()->{
                            var permutation = invalidationTask(current);
                            if (permutation.changed) {
                                var latch = new CountDownLatch(1);
                                Platform.runLater(() -> {
                                    LOGGER.fine("Firing permutation on main UI thread.");
                                    firePermutationChange(permutation.permutation);
                                    latch.countDown();
                                    LOGGER.fine("Finished firing permutation on main UI thread.");
                                });
                                try {
                                    latch.await();
                                } catch (InterruptedException ex) {
                                    Logger.getLogger(SortedList.class.getName()).log(Level.WARNING, "Interrupt while waiting for UI to finish permutation.", ex);
                                    Thread.currentThread().interrupt();
                                }
                            }
                        });
                    } else {
                        var permutation = invalidationTask(current);
                        if (permutation.changed) {
                            firePermutationChange(permutation.permutation);
                        }
                    }                    
                    LOGGER.log(Level.FINE, "Finished invalidating list.");
                }

                @Override
                public Object getBean() {
                    return SortedList.this;
                }

                @Override
                public String getName() {
                    return "comparator";
                }
            };
        }
        return comparator;
    }
    
    /**
     * Anytime the comparatorProperty is changed this method will be called.
     * This will update the appropriate lists and create a "permutation" if 
     * necessary for downstream listeners.
     * @param current The current comparator if set.
     */
    private synchronized Permutation invalidationTask(Comparator<? super E> current) {
        LOGGER.fine("Starting invalidation task.");
        var lastSorted = new ArrayList<Element<E>>(sorted.size());
        var lastFiltered = new ArrayList<Element<E>>();
        var lastUnsorted = new ArrayList<Element<E>>(unsorted.size());

        boolean hadComparator = false;
        if (applyFilter && !filteredList.filtered().isEmpty()) {
            lastFiltered.addAll(filteredList.filtered());
        }         
        if (elementComparator != null) {
            hadComparator = true;
            lastSorted.addAll(sorted);
            LOGGER.fine("Start clearing the list in invalidationTask.");
            sorted.clear();
            sorted.addAll(unsorted);
            LOGGER.fine("Done clearing the list in invalidationTask.");
        }
        lastUnsorted.addAll(unsorted);

        if (current != null) {
            elementComparator = new ElementComparator<>(current);
            sorted.sort(elementComparator);
            if (applyFilter) {
                filteredList.sort(elementComparator);
            }
        } else {
            elementComparator = null;
        }
        if (applyFilter) {
             filteredList.load(sorted, elementComparator);
        }
        LOGGER.fine("Finished invalidation task.");
        return doPermutation(hadComparator, lastSorted, lastUnsorted, lastFiltered);
    }
    
    protected static record Permutation(boolean changed, int[] permutation, ArrayList<IdAndIndex> originalIndexes, ArrayList<IdAndIndex> purmutationIndexes) {
        
    }

    /**
     * Checks current state to determine what lists to use when
     * creating the permutation.
     * @param hadComparator Did this class have a previous comparator before 
     *                      invalidating and setting a new one.
     * @param lastSorted The previously sorted list.
     * @param lastUnsorted The previously unsorted list.
     * @param lastFiltered The previously filtered list.
     * @return 
     */
    private Permutation doPermutation(boolean hadComparator, 
            List<Element<E>> lastSorted, List<Element<E>> lastUnsorted,
            List<Element<E>> lastFiltered) {
       List<Element<E>> lastList = lastUnsorted;
       List<Element<E>> currentList = unsorted;

       if (applyFilter) {
           lastList = lastFiltered;
           currentList = filteredList.filtered();
       } else if (hadComparator) {
           lastList = lastSorted;
       }       
       if (elementComparator != null && !applyFilter) {
           currentList = sorted;
       } else if (elementComparator == null && applyFilter) {
           currentList = filteredList.filtered();
       }
       Permutation permutation;
       if (elementComparator != null) {
           permutation = buildPermutation(lastList, currentList);
           sortedById.clear();
           sortedById.addAll(permutation.purmutationIndexes);            
       } else {
           permutation = buildPermutation(currentList, lastList);
       }
       return permutation;
       
    }  
    
    /**
     * Compare the original list to the current "permutation" after the change
     * has been applied.
     * @param original
     * @param permutation
     * @return 
     */
    private Permutation buildPermutation(List<Element<E>> original, List<Element<E>> permutation) {
        var originalSortById = new ArrayList<IdAndIndex>(original.size());
        var permutationById = new ArrayList<IdAndIndex>(permutation.size());
        var perm = new int[permutation.size()];
        for (int i = 0; i < original.size(); i++) {
                originalSortById.add(new IdAndIndex(original.get(i).id(), i));
        }
        for (int i = 0; i < permutation.size(); i++) {
                permutationById.add(new IdAndIndex(permutation.get(i).id(), i));
        }
        originalSortById.sort(idComparator);    
        boolean changed = false;
        for (int i = 0; i < permutationById.size(); i++) {
            var aPermutation = permutationById.get(i);
            var lastIndex = Collections.binarySearch(originalSortById, new IdAndIndex(aPermutation.id(), i), idComparator);
            var permutationIndex = originalSortById.get(lastIndex).index();
            if (aPermutation.index() != permutationIndex) {
                changed = true;
            }
            perm[i] = permutationIndex;
        }
        permutationById.sort(idComparator);
        return new Permutation(changed, perm, originalSortById, permutationById);
    }

    /**
     * If the list has changed after being invalidated (like a new filter, or sort)
     * then notify everyone passing the permutation indexes.
     * @param perm The indexes that changed.
     */
    private void firePermutationChange(int[] perm) {
        if (applyFilter) {
            fireChange(new SimplePermutationChange<>(0, filteredList.size(), perm, this));
        } else if (elementComparator != null) {
            fireChange(new SimplePermutationChange<>(0, sorted.size(), perm, this));
        } else {
            fireChange(new SimplePermutationChange<>(0, unsorted.size(), perm, this));
        }
    }

    public final Comparator<? super E> getComparator() {
        return comparator == null ? null : comparator.get();
    }

    /**
     * Setting the comparator invalidates the list and will apply the change
     * to the list.
     * @param comparator 
     */
    public final void setComparator(Comparator<? super E> comparator) {
        comparatorProperty().set(comparator);
    }

    /**
     * Returns the element at the specified position in this list.
     *
     * @param index index of the element to return
     * @return the element at the specified position in this list
     * @throws IndexOutOfBoundsException {@inheritDoc}
     */
    @Override
    public synchronized E get(int index) {
        if (applyFilter) {
            return filteredList.filtered().get(index).element();
        } else if (elementComparator != null) {
            if (index >= sorted.size()) {
                LOGGER.log(Level.WARNING, "IndexOutOFBoundsException index: {0}, sortedSize: {1}", new Object[]{index, sorted.size()});
                throw new IndexOutOfBoundsException();
            }
            return sorted.get(index).element();
        }
        return unsorted.get(index).element();
    }

    /**
     * Returns the number of elements in this list.
     *
     * @return the number of elements in this list
     */
    @Override
    public int size() {
        if (applyFilter) {
            return filteredList.size();
        }
        return size.get();       
    }

    /**
     * The current list may differ from the original list (sorted, filtered).
     * Given a view index, this will find the index from the source list.
     * @param viewIndex The index from the view that corresponds to the list for
     *                  the current state of this object.
     * @return The index from the original (unsorted) list.
     */
    @Override
    public synchronized int getSourceIndex(int viewIndex) {
        if (applyFilter) {
            if (viewIndex < filteredList.filtered().size()) {
                var element = filteredList.filtered().get(viewIndex);
                var unsortedIndex =  Collections.binarySearch(unsortedById, new IdAndIndex(element.id(), 0), idComparator);
                return unsortedById.get(unsortedIndex).index();
            } 
            return -1;
        } else if (elementComparator != null) {
            var element = sorted.get(viewIndex);
            var unsortedIndex =  Collections.binarySearch(unsortedById, new IdAndIndex(element.id(), 0), idComparator);
            return unsortedById.get(unsortedIndex).index();
        }
        return viewIndex;
    }

    /**
     * Given an index from the source list, return the index for that record
     * in the list set for the current state of this class (sorted, filtered).
     * @param sourceIndex
     * @return The index for the same record from the source list.
     */
    @Override
    public synchronized int getViewIndex(int sourceIndex) {
        var element = unsorted.get(sourceIndex);
        if (applyFilter) {
            var index =  Collections.binarySearch(filteredList.filteredById(), new IdAndIndex(element.id(), 0), idComparator);
            if (index >= 0) {
                return filteredList.filteredById().get(index).index();
            }
            return index;
        } else if (elementComparator != null) {
            var sortedIndex =  Collections.binarySearch(sortedById, new IdAndIndex(element.id(), 0), idComparator);
            return sortedById.get(sortedIndex).index();
        }
        return sourceIndex;
    }
    
    /**
     * Re-index a list, and order the list by the arbitrary ID associated with
     * each record. These index lists allow fast look-ups.
     * @param elements The elements to index.
     * @param indexes The list of indexes to update.
     */
    private void updateIndexes(List<Element<E>> elements, ArrayList<IdAndIndex> indexes) {
        indexes.clear();
        indexes.ensureCapacity(elements.size());
        for (int i = 0; i < elements.size(); i++) {
            indexes.add(new IdAndIndex(elements.get(i).id(), i));
        }
        indexes.sort(idComparator);
    }

    /**
     * Check what kind of change is being made and apply the change, updating
     * all indexes appropriately.
     * @param c
     * @return 
     */
    private Runnable update(Change<? extends E> c) {        
        LOGGER.fine("Processing change in update method.");
        Runnable updateNotification = null;
        if (c.wasPermutated()) {
            doPermutation(c);
        } 
        if (c.wasRemoved()) {
            updateNotification = doRemove(c);
        }
        if (c.wasAdded()) {
            updateNotification = doAdd(c);
        }
        if (c.wasUpdated()) {
            updateNotification = doUpdate(c);
        }
        if (c.wasAdded()) {
            LOGGER.log(Level.FINE, "Updating indexes for add change. doAdd method updates sorted and filteredList.");
            updateIndexes(unsorted, unsortedById);
        } else {
            LOGGER.log(Level.FINE, "Updating inexes for non add change.");
            ForkJoinTask.invokeAll(ForkJoinTask.adapt(()->{
                updateIndexes(sorted, sortedById);
            }), ForkJoinTask.adapt(()->{
                updateIndexes(unsorted, unsortedById);
            }), ForkJoinTask.adapt(()->{
                updateIndexes(filteredList.filtered(), filteredList.filteredById());
            }));
        }
        LOGGER.fine("Finished processing change in update method.");
        return updateNotification;
    }

    /**
     * If a upstream (source) list was permutated, then update the list(s)
     * appropriately. A permutation is a change that did not add or remove elements,
     * but just changed the existing elements in some other way. Since this list
     * keeps a copy of the source list, we must update our copy of the source list.
     * @param c The change requested (includes the range of items changed in the source list).
     */
    private void doPermutation(Change<? extends E> c) {
        LOGGER.fine("Starting Permutation.");
        Element<E>[] unsortedTemp = new Element[unsorted.size()];
        for (int i = 0; i < unsorted.size(); ++i) {
            if (i >= c.getFrom() && i < c.getTo()) {
                int p = c.getPermutation(i);
                var element = unsorted.get(i);
                unsortedTemp[p] = new Element(element.id(), element.element());
            } else {
                unsortedTemp[i] = unsorted.get(i);
            }
        }
        unsorted.clear();
        unsorted.addAll(Arrays.asList(unsortedTemp));
        LOGGER.fine("Finished permutation.");
    }

    /**
     * Remove one or more elements from the list.
     * @param c The change for this remove. Includes a list of elements to remove.
     * @return A method that gets called on the main JavaFX thread to notify downstream lists
     *          of the changes.
     */
    private Runnable doRemove(Change<? extends E> c) {
        LOGGER.fine("Starting remove.");
        final int removedTo = c.getFrom() + c.getRemovedSize();
        var elementsToRemove = new ArrayList<Element<E>>(c.getRemovedSize());
        elementsToRemove.addAll(unsorted.subList(c.getFrom(), removedTo));
        var removeIndexes = new ArrayList<IndexAndElement<E>>();
        var removeFilteredIndexes = new ArrayList<Element<E>>();
        ForkJoinTask.invokeAll(ForkJoinTask.adapt(()->{
            if (elementComparator != null) {
                buildRemoveIndexesSorted(elementsToRemove, removeIndexes);
                removeIndexes.forEach(index -> {
                    sorted.remove((int)index.index);                    
                });
            }
        }), ForkJoinTask.adapt(()->{
            for (int i = removedTo - 1; i >= c.getFrom(); i--) {
                unsorted.remove(i);
                if (elementComparator == null) {
                    sorted.remove(i);
                }
            }
        }), ForkJoinTask.adapt(()->{
            removeFilteredIndexes.addAll(filteredList.doRemove(elementsToRemove));
        }));
        var hadComparator = elementComparator != null;
        var wasFiltered = applyFilter;
        LOGGER.fine("Finished remove.");
        return ()->{
            LOGGER.fine("Start notify nextRemove.");
            if (wasFiltered) {
                removeFilteredIndexes.forEach(index -> {
                    nextRemove((int)index.id(), index.element());
                });
            } else if (hadComparator) {
                removeIndexes.forEach(index -> {
                    nextRemove(index.index, index.element);
                });
            }
            LOGGER.fine("Finish notify nextRemove.");
        };
    }
    
    private void buildRemoveIndexesSorted(List<Element<E>> elementsToRemove, List<IndexAndElement<E>> removeIndexes) {
        for (int i = 0; i < elementsToRemove.size(); i++) {
            var e = elementsToRemove.get(i);
            var searchResult = Collections.binarySearch(sortedById, new IdAndIndex(e.id(), 0), idComparator);
            removeIndexes.add(new IndexAndElement<>(sortedById.get(searchResult).index(), e.element()));
        }
        Collections.sort(removeIndexes, (e1, e2)-> Long.compare(e2.index, e1.index));
    }
    
    /**
     * Add one or more elements from the list.
     * @param c The change for this remove. Includes a list of elements to remove.
     * @return A method that gets called on the main JavaFX thread to notify downstream lists
     *          of the changes.
     */
    private Runnable doAdd(Change<? extends E> c) {
        LOGGER.log(Level.FINE, "Starting Add.");
        sorted.ensureCapacity(sorted.size() + c.getAddedSubList().size());
        unsorted.ensureCapacity(unsorted.size() + c.getAddedSubList().size());
        
        var add = c.getAddedSubList().stream()
                .map((element) -> {
                    var e = new Element<E>(lastId++, element);
                    if (elementComparator != null) {
                        var pos = Collections.binarySearch(sorted, e, elementComparator);
                        if (pos < 0) {
                            pos = ~pos;
                        }
                        sorted.add(pos, e);                        
                    }
                    if (applyFilter) {
                        filteredList.doSortedAdd(e, elementComparator);
                    }
                    return e;
                }).toList();
        if (elementComparator == null) {
            sorted.addAll(c.getFrom(), add);
        }
        unsorted.addAll(c.getFrom(), add);
        if (applyFilter && elementComparator == null) {
            filteredList.doAdd(add, c.getFrom());
        }
        var updatedIndexes = new ArrayList<>(updateAddedIndexes(add));
        LOGGER.log(Level.FINE, "Finished add.");
        return () -> {
            LOGGER.log(Level.FINE, "size in lambda {0}", size());
            updatedIndexes.forEach((i)->nextAdd(i, i + 1));
            LOGGER.log(Level.FINE, "lambda done {0}", size());
        };
    }
    
    /**
     * Update indexes after an add. Call find to return the updated indexes
     * so that we can notify downstream table transformations via nextAdd.
     * @param add
     * @return The list of indexes added.
     */
    private List<Integer> updateAddedIndexes(List<Element <E>> add) {
        updateIndexes(sorted, sortedById);
        if (applyFilter) {
            updateIndexes(filteredList.filtered(), filteredList.filteredById());  
            return findAddedIndexes(add, filteredList.filteredById());
        }
        return findAddedIndexes(add, sortedById);
    }
    
    /**
     * Search for the index of each added item.
     * @param add
     * @param indexes
     * @return The list of indexes added.
     */
    List<Integer> findAddedIndexes(List<Element <E>> add, ArrayList<IdAndIndex> indexes) {
        var addedIndexes = add.parallelStream().map(addedElement-> {
            return Collections.binarySearch(indexes, new IdAndIndex(addedElement.id(), 0), (e1, e2)->Long.compare(e1.id(), e2.id()));
        }).filter((index)->index >=0).map((searchResult)->indexes.get(searchResult).index()).collect(Collectors.toList());
        addedIndexes.sort((i1, i2)->Integer.compare(i1, i2));
        return addedIndexes;
    }
    
    /**
     * Depending on the state of this SortedList, find the current list being used.
     * @return The current list being used, based on the current state of the SortedList.
     */
    private ArrayList<Element<E>> getLastList() {
        var lastList = new ArrayList<Element<E>>();
        if (applyFilter && !filteredList.filtered().isEmpty()) {
            lastList.addAll(filteredList.filtered());
        } else if (elementComparator != null) {                
            lastList.addAll(sorted);            
        } else {
            lastList.addAll(unsorted);
        }
        return lastList;
    }
    
    /**
     * Perform the update on this list.
     * @param c
     * @return A method that gets called on the main JavaFX thread to notify downstream lists
     *          of the changes. 
     */
    private Runnable doUpdate(Change<? extends E> c) {
        LOGGER.log(Level.FINE, "Starting update.");
        var lastList = getLastList();
        
        var updateIndexes = new ArrayList<Integer>();
        if (elementComparator != null) {
            for (int i = c.getFrom(), to = c.getTo(); i < to; ++i) {
                var unsortedElement = unsorted.get(i);
                var sortedByIdIndex = Collections.binarySearch(sortedById, new IdAndIndex(unsortedElement.id(), 0), idComparator);
                var sortedElementWithId = sortedById.get(sortedByIdIndex);
                sorted.remove(sortedElementWithId.index());
                var sortedIndex = Collections.binarySearch(sorted, unsortedElement, elementComparator);
                sortedIndex = ~sortedIndex;
                sortedById.set(sortedByIdIndex, new IdAndIndex(sortedElementWithId.id(), sortedIndex));
                sorted.add(sortedIndex, unsortedElement);
                updateIndexes.add(sortedIndex);                
            }
        }
        if (applyFilter) {
            for (int i = c.getFrom(), to = c.getTo(); i < to; ++i) {
                filteredList.updateChangedElement(unsorted.get(i));
            }
            filteredList.applyUpdates(elementComparator);
        }
        var perm = applyFilter?buildPermutation(filteredList.filtered(), filteredList.lastFiltered()):buildPermutation(sorted, lastList);
        var permutation = perm.permutation;        
        var lastSize = applyFilter?filteredList.size():sorted.size();
        var hadComparator = elementComparator != null;
        var wasFiltered = applyFilter;
        var lastFiltered = filteredList;
        filteredList = new FilteredList(filteredList.filtered(), 
                                        filteredList.filteredById(),
                                        filteredList.predicate);
        LOGGER.log(Level.FINE, "Finished update.");
        return () -> {
            if (wasFiltered) {
                if (hadComparator) {
                    nextPermutation(0, lastSize, permutation);
                }
                lastFiltered.fireNotifications((index)->nextUpdate(index), 
                        (index, element)->nextRemove(index, element),
                        (begin, end)->nextAdd(begin, end));
            } else if (hadComparator) {
                nextPermutation(0, lastSize, permutation);
                updateIndexes.forEach(sortedIndex -> {
                    nextUpdate(sortedIndex);
                });
            }
        };
    }
    
    protected static class ElementComparator<E> implements Comparator<Element<E>> {

        private final Comparator<? super E> comparator;

        public ElementComparator(Comparator<? super E> comparator) {
            this.comparator = comparator;
        }

        @Override
        @SuppressWarnings("unchecked")
        public int compare(Element<E> o1, Element<E> o2) {
            return comparator.compare(o1.element(), o2.element());
        }
    }
    
    /**
     * Add a filter to the sorted list. The filter will be applied in the 
     * executor service so that in progress updates/removals can be completed
     * first. 
     * 
     * The filter work will then be done on the main JavaFX thread so that
     * changes are reflected immediately in the UI.
     *
     * To show all the records call clearFilter or deleteFilter.
     * 
     * @param predicate
     */
    public void setFilter(Predicate<E> predicate) {
        if (threaded) {
            es.submit(() -> {
                applyPredicateChange(predicate);
                var elementsToRemove = filteredList.getElements();
                threadRemoveFilteredElements(elementsToRemove);
                filteredList.reset();
                loadFilteredList();
                threadNotifyUiFilteredChange(predicate);
            });
        } else {
            applyPredicateChange(predicate);
            var elementsToRemove = filteredList.getElements();
            removeFilteredElements(elementsToRemove);
            filteredList.reset();
            loadFilteredList();
            notifyUiFilteredChange(predicate);
        }
    }
    
    private void applyPredicateChange(Predicate<E> predicate) {
        filteredList.setPredicate(predicate);
        if (predicate == null) {
            applyFilter = false;
        } else {
            applyFilter = true;
        }
    }
    
    private void threadRemoveFilteredElements(List<E> elementsToRemove) {
        var latch = new CountDownLatch(1);
        Platform.runLater(()-> {
            removeFilteredElements(elementsToRemove);
            latch.countDown();
        });
        try {
            latch.await();
        } catch (InterruptedException ex) {
            LOGGER.log(Level.WARNING, "Error waiting for JavaFX thread to clear table.", ex);
            Thread.currentThread().interrupt();
        }
    }
    
    private void removeFilteredElements(List<E> elementsToRemove) {
        beginChange();
        LOGGER.fine("Removing all elements from TableView.");
        nextRemove(0, elementsToRemove);
        LOGGER.fine("Finished removing all elements from TableView.");        
    }
    
    private void loadFilteredList() {
        if (applyFilter) {
            LOGGER.fine("Loading records into filteredList.");
            if (comparator != null) {
                filteredList.load(sorted, elementComparator);
            } else {
                filteredList.load(unsorted, elementComparator);
            }
            LOGGER.fine("Done loading records into filteredList.");
        }
    }
    
    private void threadNotifyUiFilteredChange(Predicate<E> predicate) {
        var latch = new CountDownLatch(1);
        var elementsToRemove = filteredList.getElements();
        Platform.runLater(()-> {
            LOGGER.fine("Adding new filteredList back into TableView on UI thread.");
            notifyUiFilteredChange(predicate);
            latch.countDown();
            LOGGER.fine("Finished adding new filteredList back into TableView on UI thread.");
        });
        try {        
            latch.await();
        } catch (InterruptedException ex) {
            LOGGER.log(Level.WARNING, "Error waiting for JavaFX thread to notify table.", ex);
            Thread.currentThread().interrupt();
        }
    }
    private void notifyUiFilteredChange(Predicate<E> predicate) {
        if (predicate != null) {
            LOGGER.fine("Adding new filteredList back into TableView.");
            nextAdd(0, filteredList.size());
            LOGGER.fine("Done adding new filteredList back into TableView.");
        } else {
            if (elementComparator != null) {
                LOGGER.fine("Adding sorted table back into TableView.");
                nextAdd(0, sorted.size());   
                LOGGER.fine("Done adding sorted table back into TableView.");
            } else {
                LOGGER.fine("Adding unsorted table back into TableView.");
                nextAdd(0, unsorted.size());
                LOGGER.fine("Done adding unsorted table back into TableView.");
            }
        }
        endChange();        
    }
    
    public boolean getApplyFilter() {
        return applyFilter;
    }
           
}
