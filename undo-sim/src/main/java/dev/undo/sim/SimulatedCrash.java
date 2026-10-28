package dev.undo.sim;

/**
 * Thrown at a blocking point or crash point to kill a simulated worker. Extends Error so engine
 * code (which never catches Error) unwinds without running further engine code (Section 21.10).
 */
public final class SimulatedCrash extends Error {
  public SimulatedCrash(String where) {
    super(where, null, false, false);
  }
}
