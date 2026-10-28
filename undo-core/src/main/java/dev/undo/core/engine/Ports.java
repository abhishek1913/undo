package dev.undo.core.engine;

import dev.undo.core.port.BugFlags;
import dev.undo.core.port.Clock;
import dev.undo.core.port.ConnectorRegistry;
import dev.undo.core.port.CrashPoints;
import dev.undo.core.port.Entropy;
import dev.undo.core.port.Ids;
import dev.undo.core.port.Notifier;
import dev.undo.core.port.Store;
import dev.undo.core.port.Waiter;

/** The ports every engine service needs, bundled so wiring stays short. */
public record Ports(
    Clock clock,
    Ids ids,
    CrashPoints crash,
    Store store,
    ConnectorRegistry registry,
    Notifier notifier,
    BugFlags bugs,
    Entropy entropy,
    Waiter waiter,
    Metrics metrics) {}
