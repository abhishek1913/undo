package dev.undo.core.policy;

/** A validation error pinned to a YAML line (1-based). */
public record PolicyError(int line, String message) {}
