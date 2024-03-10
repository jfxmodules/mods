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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener.Change;
import javafx.collections.ObservableList;

/**
 * Class used to create a copy of a Change when SortedList is in threaded mode.
 * @author Chad Preisler
 * @param <E>
 */
public final class ThreadSafeChange<E> extends Change<E> {

    private final List<ChangeRecord<E>> changes;
    private int cursor = -1;
    
    record ChangeRecord<E>(List<E> removed,
                        List<E> added,
                        int from,
                        int to,
                        int[] perm) {
    };

    public static final <E> ThreadSafeChange create(Change<E> change) {
        ObservableList<E> listInstance = FXCollections.observableArrayList();
        listInstance.addAll(change.getList());
        
        List<ChangeRecord<E>> changes = new ArrayList<ChangeRecord<E>>();
        while(change.next()) {
            var removed = new ArrayList<E>(change.getRemoved());
            var added = new ArrayList<E>(change.getAddedSubList());
            
            int[] perm;
            if (change.wasPermutated()) {
                var permSize = change.getTo() - change.getFrom();
                perm = new int[permSize];
                var permCount = 0;
                for( int i = change.getFrom(); i < change.getTo(); i++) {
                    perm[permCount] = i;
                    permCount++;
                }
            } else {
                perm = new int[0];
            }
            var changeRecord = new ChangeRecord(removed, added,
                    change.getFrom(),
                    change.getTo(),
                    perm);
            changes.add(changeRecord);            
        }
        change.reset();
        return new ThreadSafeChange(change.getList(), changes);
    }

    private ThreadSafeChange(ObservableList<E> source, List<ChangeRecord<E>> changes) {
        super(source);
        this.changes = changes;
    }

    @Override
    public boolean next() {
        if (cursor + 1 < changes.size()) {
            ++cursor;
            return true;
        }
        return false;
    }

    @Override
    public void reset() {
        cursor = -1;
    }

    @Override
    public int getFrom() {
        return changes.get(cursor).from;
    }

    @Override
    public int getTo() {
        return changes.get(cursor).to;
    }

    @Override
    public List<E> getRemoved() {
        return changes.get(cursor).removed;
    }
     
    @Override
    public List<E> getAddedSubList() {
        return wasAdded()? changes.get(cursor).added : Collections.<E>emptyList();
    }

    public int getAddedSize() {
            return wasAdded() ? changes.get(cursor).added.size() : 0;
    }
    
    @Override
    protected int[] getPermutation() {
        return changes.get(cursor).perm;
    }
    
    private void checkState() {
        if (cursor == -1) {
            throw new IllegalStateException("Invalid Change state: next() must be called before inspecting the Change.");
        }
    }    

    /* @Override
    public String toString() {
        String ret;
        if (wrappedChange.perm.length != 0) {
            ret = wrappedChange.permChangeToString(change.perm);
        } else if (wrappedChange.wasUpdated()) {
            ret = ChangeHelper.updateChangeToString(change.from, change.to);
        } else {
            ret = ChangeHelper.addRemoveChangeToString(change.from, change.to, getList(), change.removed);
        }
        return "{ " + ret + " }";
    }*/
}
