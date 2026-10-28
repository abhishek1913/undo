package dev.undo.core.port;

import java.util.UUID;

/** UUIDv7 in the server; seeded in the simulator. Never generate ids in the database. */
public interface Ids {
  UUID next();
}
