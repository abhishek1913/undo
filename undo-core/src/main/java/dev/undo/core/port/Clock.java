package dev.undo.core.port;

import java.time.Instant;

/** The only source of time for core code. Server: system clock. Simulator: virtual time. */
public interface Clock {
  Instant now();
}
