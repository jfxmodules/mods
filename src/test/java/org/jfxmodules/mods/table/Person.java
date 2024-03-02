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
import java.util.List;
import java.util.stream.Collectors;
import javafx.beans.Observable;
import javafx.beans.property.StringProperty;
import javafx.beans.property.StringPropertyBase;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

public class Person implements Comparable<Person> {
    public StringProperty name = new StringPropertyBase("foo") {

        @Override
        public Object getBean() {
            return Person.this;
        }

        @Override
        public String getName() {
            return "name";
        }
    };

    public Person(String name) {
        this.name.set(name);
    }

    public Person() {
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        final Person other = (Person) obj;
        if (this.name.get() != other.name.get() && (this.name.get() == null || !this.name.get().equals(other.name.get()))) {
            return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        int hash = 7;
        hash = 59 * hash + (this.name.get() != null ? this.name.get().hashCode() : 0);
        return hash;
    }

    @Override
    public String toString() {
        return "Person[" + name.get() + "]";
    }

    @Override
    public int compareTo(Person o) {
        return this.name.get().compareTo(o.name.get());
    }

    public static ObservableList<Person> createPersonsList(Person... persons) {
        ObservableList<Person> list = FXCollections.observableArrayList((Person p) -> new Observable[]{p.name});
        list.addAll(persons);
        return list;
    }

    public static List<Person> createPersonsFromNames(String... names) {
        return Arrays.asList(names).stream().
                map(name -> new Person(name)).collect(Collectors.toList());
    }

    public static ObservableList<Person> createPersonsList(String... names) {
        ObservableList<Person> list = FXCollections.observableArrayList((Person p) -> new Observable[]{p.name});
        list.addAll(createPersonsFromNames(names));
        return list;
    }
}
