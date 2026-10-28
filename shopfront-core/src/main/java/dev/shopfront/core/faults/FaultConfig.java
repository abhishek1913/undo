package dev.shopfront.core.faults;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.shopfront.core.json.Json;

/** Switchable provider faults (Section 12.5). All zero = "Calm". */
public record FaultConfig(
    int latencyMsMin,
    int latencyMsMax,
    double error5xxRate,
    boolean includeReads,
    double rateLimitRate,
    double dropAfterCommitRate,
    double hangAfterCommitRate,
    int hangMs,
    double webhookDropRate,
    double webhookDuplicateRate,
    int webhookDelayMsMax,
    double settlementFailureRate) {

  public static final FaultConfig CALM =
      new FaultConfig(0, 0, 0, false, 0, 0, 0, 15000, 0, 0, 0, 0);

  public static FaultConfig flakyNetwork() {
    return new FaultConfig(50, 300, 0.05, false, 0, 0.2, 0, 15000, 0, 0, 0, 0);
  }

  /** Section 22.5 step 2. */
  public static FaultConfig chaos() {
    return new FaultConfig(0, 0, 0.05, false, 0.02, 0.1, 0.02, 15000, 0.1, 0.1, 0, 0.05);
  }

  /** Omitted fields keep their current value. */
  public FaultConfig merge(JsonNode p) {
    return new FaultConfig(
        p.path("latency_ms_min").asInt(latencyMsMin),
        p.path("latency_ms_max").asInt(latencyMsMax),
        rate(p, "error_5xx_rate", error5xxRate),
        p.path("include_reads").asBoolean(includeReads),
        rate(p, "rate_limit_rate", rateLimitRate),
        rate(p, "drop_after_commit_rate", dropAfterCommitRate),
        rate(p, "hang_after_commit_rate", hangAfterCommitRate),
        p.path("hang_ms").asInt(hangMs),
        rate(p, "webhook_drop_rate", webhookDropRate),
        rate(p, "webhook_duplicate_rate", webhookDuplicateRate),
        p.path("webhook_delay_ms_max").asInt(webhookDelayMsMax),
        rate(p, "settlement_failure_rate", settlementFailureRate));
  }

  private static double rate(JsonNode p, String field, double current) {
    if (!p.has(field)) {
      return current;
    }
    double v = p.get(field).asDouble();
    if (v < 0 || v > 1) {
      throw new IllegalArgumentException(field + " must be between 0 and 1");
    }
    return v;
  }

  public ObjectNode toJson() {
    ObjectNode o = Json.obj();
    o.put("latency_ms_min", latencyMsMin);
    o.put("latency_ms_max", latencyMsMax);
    o.put("error_5xx_rate", error5xxRate);
    o.put("include_reads", includeReads);
    o.put("rate_limit_rate", rateLimitRate);
    o.put("drop_after_commit_rate", dropAfterCommitRate);
    o.put("hang_after_commit_rate", hangAfterCommitRate);
    o.put("hang_ms", hangMs);
    o.put("webhook_drop_rate", webhookDropRate);
    o.put("webhook_duplicate_rate", webhookDuplicateRate);
    o.put("webhook_delay_ms_max", webhookDelayMsMax);
    o.put("settlement_failure_rate", settlementFailureRate);
    return o;
  }

  public static FaultConfig fromJson(JsonNode n) {
    return n == null || n.isNull() ? CALM : CALM.merge(n);
  }
}
