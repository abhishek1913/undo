package dev.undo.core.model;

import java.util.UUID;

public final class Posting extends Row<Posting> {
  public long id;
  public UUID entryId;
  public String account;

  /** + debit, - credit. Never 0. */
  public long amountMinor;

  public String currency = "INR";
}
