package dev.undo.core.engine;

import com.fasterxml.jackson.databind.JsonNode;
import dev.undo.core.model.Action;

/** Short one-line descriptions of actions for events and reports. */
public final class Summaries {
  private Summaries() {}

  public static String of(Action a) {
    StringBuilder sb = new StringBuilder(a.tool);
    if (a.moneyOutMinor > 0) {
      sb.append(' ').append(Money.format(a.moneyOutMinor));
    } else if (a.args != null && a.args.path("amount_minor").isIntegralNumber()) {
      sb.append(' ').append(Money.format(a.args.get("amount_minor").asLong()));
    }
    JsonNode args = a.args;
    for (String key :
        new String[] {"order_id", "ticket_id", "customer_id", "refund_id", "store_credit_id"}) {
      if (args != null && args.path(key).isTextual()) {
        sb.append(' ').append(args.get(key).asText());
        break;
      }
    }
    return sb.toString();
  }
}
