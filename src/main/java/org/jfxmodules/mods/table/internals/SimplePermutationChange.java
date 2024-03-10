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

import java.util.Collections;
import java.util.List;
import javafx.collections.ListChangeListener.Change;
import javafx.collections.ObservableList;

/**
 *
 * @author Chad Preisler
 */
public class SimplePermutationChange<E> extends Change<E> {

    private final int from;
    private final int to;
    private boolean invalid = true;
    private final int[] permutation;

    public SimplePermutationChange(int from, int to, int[] permutation, ObservableList<E> list) {
        super(list);
        this.from = from;
        this.to = to;
        this.permutation = permutation;
    }

    @Override
    public boolean next() {
        if (invalid) {
            invalid = false;
            return true;
        }
        return false;
    }

    @Override
    public void reset() {
        invalid = true;
    }

    @Override
    public int getFrom() {
        return from;
    }

    @Override
    public int getTo() {
        return to;
    }

    public void checkState() {
        if (invalid) {
            throw new IllegalStateException("Invalid Change state: next() must be called before inspecting the Change.");
        }
    }

    @Override
    public List<E> getRemoved() {
        checkState();
        return Collections.<E>emptyList();
    }

    @Override
    protected int[] getPermutation() {
        checkState();
        return permutation;
    }
}
