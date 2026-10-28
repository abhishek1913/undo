package dev.undo.core.model;

import java.util.UUID;

public final class RevertItem extends Row<RevertItem> {
  public UUID id;
  public UUID planId;
  public int orderNo;
  public UUID targetActionId;
  public ItemKind kind;
  public ItemStatus status;
  public UUID compensationActionId;
  public UUID remediationActionId;
  public long moneyRecoveredMinor;
  public long moneyNotRecoveredMinor;
  public String note;
}
