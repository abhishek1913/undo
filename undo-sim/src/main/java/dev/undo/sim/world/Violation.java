package dev.undo.sim.world;

/** One broken invariant in one seed. */
public record Violation(long seed, String invariant, String message) {}
