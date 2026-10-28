package dev.undo.core.connector;

import java.util.List;

/** Lines must sum to 0. */
public record JournalSpec(String kind, String source, List<PostingLine> lines) {}
