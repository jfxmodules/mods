# jfxmodules/mods

This project provides JavaFX collection utilities and table helpers. One of the main types is `SortedList`, which wraps an `ObservableList` and keeps it sorted and optionally filtered while preserving source-list change notifications.

## SortedList usage

`org.jfxmodules.mods.table.SortedList` extends JavaFX's `TransformationList`, so it can be used anywhere an `ObservableList` is accepted, including `TableView` items.

### Basic sorting

```java
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.jfxmodules.mods.table.SortedList;

ObservableList<String> source = FXCollections.observableArrayList(
    "beta", "alpha", "gamma"
);

SortedList<String> sorted = new SortedList<>(source, String::compareTo);

System.out.println(sorted); // [alpha, beta, gamma]
```

The constructor accepts the source list and a `Comparator`. If you pass `null`, the list remains in source order.

### Sorting a JavaFX TableView

```java
import javafx.scene.control.TableView;
import org.jfxmodules.mods.table.SortedList;

SortedList<Person> sorted = new SortedList<>(people, Comparator.comparing(Person::getName));
TableView<Person> table = new TableView<>();
table.setItems(sorted);

// Bind the SortedList comparator to the TableView comparator so JavaFX sorting works correctly.
sorted.comparatorProperty().bind(table.comparatorProperty());
```

This is the recommended pattern when using `TableView` and the built-in sort policy.

### Filtering

```java
SortedList<Person> sorted = new SortedList<>(people, Comparator.comparing(Person::getName));

sorted.setFilter(person -> person.isActive());

// Remove the filter by passing null
sorted.setFilter(null);
```

`setFilter` applies a predicate to the visible list while keeping the underlying source list intact.

### Fast sorting with cached string keys

For large lists, `SortedList` can sort by a cached string key instead of re-running the full comparator for every comparison:

```java
SortedList<Person> sorted = new SortedList<>(people, null);

sorted.setSortKey(Person::getDisplayName, String.CASE_INSENSITIVE_ORDER);
```

This is useful when you want a consistent sort key without creating a custom comparator for every object.

### Replacing the comparator

```java
SortedList<String> sorted = new SortedList<>(words, Comparator.naturalOrder());

sorted.setComparator(String.CASE_INSENSITIVE_ORDER);
```

You can also set the comparator back to natural source order by passing `null`.

### Threaded mode

For larger updates, `SortedList` supports a threaded constructor:

```java
SortedList<String> sorted = new SortedList<>(source, Comparator.naturalOrder(), true);
```

Threaded mode performs heavier list operations on a background executor, which can help when many items are added or removed in bulk.

## Example model

```java
public record Person(String name, boolean active) {
    public String getName() { return name; }
    public boolean isActive() { return active; }
    public String getDisplayName() { return name; }
}
```

## Notes

- `SortedList` is designed to wrap an `ObservableList` and automatically react to changes from the source list.
- It supports both sorting and filtering in the same view.
- It can be used directly as the items list for a `TableView` or any other JavaFX list-backed control.
