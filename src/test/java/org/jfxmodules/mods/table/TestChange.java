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
import java.util.List;
import javafx.collections.ListChangeListener.Change;
import javafx.collections.ObservableList;

/**
 *
 * @author Chad Preisler
 */
public class TestChange<E> extends Change<E> {
    int changeCount = 0;
    List<E> removed = new ArrayList<E>();
    
    public TestChange(ObservableList<E> ol) {
        super(ol);
    }
    
    @Override
    public boolean next() {
        if (changeCount == 0) {
            changeCount++;
            return true;
        }
        return false;
    }

    @Override
    public void reset() {
        changeCount = 0;
    }

    @Override
    public int getFrom() {
        return 0;
    }

    @Override
    public int getTo() {
        return 100;
    }

    @Override
    public List<E> getRemoved() {
        checkState();
        return removed;
    }

    @Override
    protected int[] getPermutation() {
        checkState();
        return new int[] {0};
    }
    
    private void checkState() {
        if (changeCount > 1) {
            throw new IllegalStateException("Invalid Change state: next() must be called before inspecting the Change.");
        }
    }
    
}
