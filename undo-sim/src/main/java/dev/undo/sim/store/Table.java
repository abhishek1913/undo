package dev.undo.sim.store;

import dev.undo.core.model.Row;
import dev.undo.core.port.ConstraintViolationException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * One table: committed rows plus secondary and unique indexes. A transaction works on a {@link
 * View} (copy-on-write overlay); commit applies the overlay atomically.
 */
final class Table<K extends Comparable<K>, R extends Row<R>> {
  record Index<R>(String name, Function<R, Object> key, boolean unique) {}

  final String name;
  final Function<R, K> pk;
  final Map<K, R> base = new HashMap<>();
  final List<Index<R>> indexes = new ArrayList<>();
  final Map<String, Map<Object, Set<K>>> indexData = new HashMap<>();

  Table(String name, Function<R, K> pk) {
    this.name = name;
    this.pk = pk;
  }

  Table<K, R> index(String name, Function<R, Object> key) {
    indexes.add(new Index<>(name, key, false));
    indexData.put(name, new HashMap<>());
    return this;
  }

  Table<K, R> unique(String name, Function<R, Object> key) {
    indexes.add(new Index<>(name, key, true));
    indexData.put(name, new HashMap<>());
    return this;
  }

  void clear() {
    base.clear();
    indexData.values().forEach(Map::clear);
  }

  /** Moves only the index entries whose key changed between the old and the new row. */
  private void reindex(R old, R row) {
    K k = pk.apply(row);
    for (Index<R> ix : indexes) {
      Object ov = old == null ? null : ix.key().apply(old);
      Object nv = ix.key().apply(row);
      if (Objects.equals(ov, nv)) {
        continue;
      }
      Map<Object, Set<K>> data = indexData.get(ix.name());
      if (ov != null) {
        Set<K> s = data.get(ov);
        if (s != null) {
          s.remove(k);
          if (s.isEmpty()) {
            data.remove(ov);
          }
        }
      }
      if (nv != null) {
        data.computeIfAbsent(nv, x -> new java.util.LinkedHashSet<>()).add(k);
      }
    }
  }

  private void indexAdd(R row) {
    K k = pk.apply(row);
    for (Index<R> ix : indexes) {
      Object v = ix.key().apply(row);
      if (v != null) {
        indexData.get(ix.name()).computeIfAbsent(v, x -> new java.util.LinkedHashSet<>()).add(k);
      }
    }
  }

  private void indexRemove(R row) {
    K k = pk.apply(row);
    for (Index<R> ix : indexes) {
      Object v = ix.key().apply(row);
      if (v != null) {
        Set<K> s = indexData.get(ix.name()).get(v);
        if (s != null) {
          s.remove(k);
          if (s.isEmpty()) {
            indexData.get(ix.name()).remove(v);
          }
        }
      }
    }
  }

  View view(Consumer<ConstraintViolationException> onViolation) {
    return new View(onViolation);
  }

  /** The table as seen inside one transaction. Rows handed out are copies. */
  final class View {
    final LinkedHashMap<K, R> writes = new LinkedHashMap<>();
    private final Consumer<ConstraintViolationException> onViolation;

    View(Consumer<ConstraintViolationException> onViolation) {
      this.onViolation = onViolation;
    }

    private R current(K k) {
      return writes.containsKey(k) ? writes.get(k) : base.get(k);
    }

    R get(K k) {
      R r = current(k);
      return r == null ? null : r.copy();
    }

    boolean exists(K k) {
      return current(k) != null;
    }

    /** Insert or update one row; unique indexes are checked now, like a Postgres statement. */
    void put(R row) {
      K k = pk.apply(row);
      for (Index<R> ix : indexes) {
        if (!ix.unique()) {
          continue;
        }
        Object v = ix.key().apply(row);
        if (v == null) {
          continue;
        }
        for (K other : candidates(ix, v)) {
          if (!other.equals(k)) {
            R o = current(other);
            if (o != null && Objects.equals(ix.key().apply(o), v)) {
              ConstraintViolationException e =
                  new ConstraintViolationException(ix.name(), "duplicate key " + v + " in " + name);
              onViolation.accept(e);
              throw e;
            }
          }
        }
      }
      writes.put(k, row.copy());
    }

    private Collection<K> candidates(Index<R> ix, Object v) {
      List<K> out = new ArrayList<>(indexData.get(ix.name()).getOrDefault(v, Set.of()));
      for (Map.Entry<K, R> w : writes.entrySet()) {
        if (Objects.equals(ix.key().apply(w.getValue()), v)) {
          out.add(w.getKey());
        }
      }
      return out;
    }

    /** Current rows (copies) whose index key equals v. */
    List<R> byIndex(String index, Object v) {
      Index<R> ix = indexes.stream().filter(i -> i.name().equals(index)).findFirst().orElseThrow();
      Set<K> keys = new java.util.LinkedHashSet<>(indexData.get(index).getOrDefault(v, Set.of()));
      keys.addAll(writes.keySet());
      List<R> out = new ArrayList<>();
      for (K k : keys) {
        R r = current(k);
        if (r != null && Objects.equals(ix.key().apply(r), v)) {
          out.add(r.copy());
        }
      }
      return out;
    }

    /** Every current row (copies). */
    List<R> all() {
      TreeSet<K> keys = new TreeSet<>(base.keySet());
      keys.addAll(writes.keySet());
      List<R> out = new ArrayList<>();
      for (K k : keys) {
        R r = current(k);
        if (r != null) {
          out.add(r.copy());
        }
      }
      return out;
    }

    void commit() {
      for (Map.Entry<K, R> w : writes.entrySet()) {
        R old = base.put(w.getKey(), w.getValue());
        reindex(old, w.getValue());
      }
    }
  }
}
