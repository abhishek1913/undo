package dev.undo.core.port;

/** Always {@link #NONE} in production. */
public interface BugFlags {
  boolean on(Bug bug);

  BugFlags NONE = bug -> false;
}
