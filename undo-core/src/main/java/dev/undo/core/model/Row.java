package dev.undo.core.model;

/**
 * Base for table rows. Rows are plain mutable records of one database row. Stores always hand out
 * copies, so changing a row in memory changes nothing until it is written back through a
 * repository. JSON fields are treated as immutable values and are shared between copies.
 */
public abstract class Row<T extends Row<T>> implements Cloneable {
  @SuppressWarnings("unchecked")
  public T copy() {
    try {
      return (T) super.clone();
    } catch (CloneNotSupportedException e) {
      throw new AssertionError(e);
    }
  }
}
