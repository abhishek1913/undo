package dev.undo.core.model;

import java.time.Instant;
import java.util.UUID;

public final class ActionTransition extends Row<ActionTransition> {
  public long id;
  public UUID actionId;
  public ActionStatus fromStatus;
  public ActionStatus toStatus;
  public long fence;
  public String actor;
  public String reason;
  public Instant at;
}
