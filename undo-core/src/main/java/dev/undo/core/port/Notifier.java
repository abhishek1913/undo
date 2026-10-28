package dev.undo.core.port;

import java.util.UUID;

/** Wakes waiting proposal calls. No-op in the simulator. */
public interface Notifier {
  void actionChanged(UUID actionId);

  Notifier NONE = id -> {};
}
