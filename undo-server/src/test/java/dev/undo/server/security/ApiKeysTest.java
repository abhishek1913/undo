package dev.undo.server.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ApiKeysTest {
  @Test
  void keysAreUndoSkPlus43Base62Characters() {
    String k = ApiKeys.generate();
    assertThat(k).matches("undo_sk_[0-9A-Za-z]{43}");
    assertThat(ApiKeys.generate()).isNotEqualTo(k);
    assertThat(ApiKeys.prefix(k)).hasSize(12).startsWith("undo_sk_");
    assertThat(ApiKeys.hash(k)).hasSize(32);
  }

  @Test
  void toolGlobs() {
    assertThat(ApiKeys.allowed(List.of("shop_*", "undo_run_status"), "shop_issue_refund")).isTrue();
    assertThat(ApiKeys.allowed(List.of("shop_get_*"), "shop_issue_refund")).isFalse();
    assertThat(ApiKeys.allowed(List.of("undo_run_status"), "undo_run_status")).isTrue();
    assertThat(ApiKeys.allowed(List.of("shop.*"), "shopXissue")).isFalse();
  }
}
