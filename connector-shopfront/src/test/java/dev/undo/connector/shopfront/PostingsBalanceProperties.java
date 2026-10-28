package dev.undo.connector.shopfront;

import static org.assertj.core.api.Assertions.assertThat;

import dev.undo.core.connector.ActionView;
import dev.undo.core.connector.JournalSpec;
import dev.undo.core.connector.PostingLine;
import dev.undo.core.json.Json;
import dev.undo.core.model.ActionKind;
import dev.undo.core.model.ActionStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.LongRange;

/** For any sequence of status changes, every entry balances and each kind posts at most once. */
class PostingsBalanceProperties {
  static final ShopfrontConnector C =
      new ShopfrontConnector(
          r -> null,
          new ShopfrontConnector.Config("k", "s", Duration.ofMinutes(15), Duration.ofSeconds(3)),
          () -> Instant.EPOCH);

  @Provide
  Arbitrary<List<String>> events() {
    return Arbitraries.of("settled", "failed", "cancelled", "compensated", "pending", "duplicate")
        .list()
        .ofMaxSize(10);
  }

  @Property
  void postingsAlwaysBalance(
      @ForAll("events") List<String> events,
      @ForAll @LongRange(min = 1, max = 100_000_000) long amount) {
    ActionView a =
        new ActionView(
            UUID.randomUUID(),
            1,
            UUID.randomUUID(),
            "shop_issue_refund",
            Json.parse(
                "{\"ticket_id\":null,\"order_id\":\"ORD-0001\",\"amount_minor\":"
                    + amount
                    + ",\"reason\":\"damaged\"}"),
            "h",
            ActionKind.PRIMARY,
            ActionStatus.SUCCEEDED,
            "k",
            "RF-1",
            "pending",
            null,
            null,
            null,
            amount,
            1);
    Set<String> posted = new HashSet<>();
    long net = 0;
    for (JournalSpec s : C.journalOnSuccess(a)) {
      net += post(s, posted);
    }
    for (String e : events) {
      List<JournalSpec> specs =
          e.equals("compensated") ? C.journalOnCompensated(a, a) : C.journalOnProviderStatus(a, e);
      for (JournalSpec s : specs) {
        net += post(s, posted);
      }
    }
    assertThat(net).isZero();
  }

  /**
   * Mirrors Ledger.write: skip (action, kind) pairs that already exist. Returns the entry's sum.
   */
  static long post(JournalSpec s, Set<String> posted) {
    if (!posted.add(s.kind())) {
      return 0;
    }
    return s.lines().stream().mapToLong(PostingLine::amountMinor).sum();
  }
}
