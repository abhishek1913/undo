package dev.undo.bench;

import com.fasterxml.jackson.databind.JsonNode;
import dev.undo.connector.shopfront.ShopfrontConnector;
import dev.undo.core.connector.Effect;
import dev.undo.core.connector.ToolSpec;
import dev.undo.core.json.Hashing;
import dev.undo.core.json.Jcs;
import dev.undo.core.json.Json;
import dev.undo.core.policy.BuiltinPolicies;
import dev.undo.core.policy.PolicyCompiler;
import dev.undo.core.policy.PolicyEvaluator;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/** B4 (policy evaluation) and B5 (JCS + hash of 1 KB args), Section 23. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 3)
@Fork(2)
public class UndoBenchmarks {
  JsonNode compiled;
  PolicyEvaluator.Context refundCtx;
  JsonNode args1k;

  @Setup
  public void setup() {
    ShopfrontConnector conn =
        new ShopfrontConnector(
            null,
            new ShopfrontConnector.Config("k", "s", Duration.ofMinutes(15), Duration.ofSeconds(3)),
            Instant::now);
    Map<String, Effect> tools = new HashMap<>();
    for (ToolSpec t : conn.tools()) {
      tools.put(t.name(), t.effect());
    }
    tools.put("undo_run_status", Effect.READ);
    PolicyCompiler.Result r =
        new PolicyCompiler(tools).compile(BuiltinPolicies.supportDefaultYaml());
    if (!r.errors().isEmpty()) {
      throw new IllegalStateException(r.errors().toString());
    }
    compiled = r.compiled();
    // A refund with the facts the Shopfront connector gathers (order, customer, ticket).
    JsonNode args =
        Json.parse(
            "{\"ticket_id\":\"TKT-0001\",\"order_id\":\"ORD-0001\",\"amount_minor\":250000,"
                + "\"reason\":\"damaged\"}");
    JsonNode facts =
        Json.parse(
            "{\"order\":{\"id\":\"ORD-0001\",\"customer_id\":\"CUS-0001\",\"status\":\"DELIVERED\","
                + "\"total_minor\":300000,\"refunded_minor\":0,\"currency\":\"INR\","
                + "\"delivered_at\":\"2026-10-01T10:00:00Z\"},"
                + "\"ticket\":{\"id\":\"TKT-0001\",\"customer_id\":\"CUS-0001\","
                + "\"order_id\":\"ORD-0001\",\"status\":\"OPEN\"},"
                + "\"customer\":{\"id\":\"CUS-0001\",\"tier\":\"standard\"}}");
    refundCtx =
        new PolicyEvaluator.Context(
            args,
            facts,
            Json.parse("{\"number\":1,\"kind\":\"HOSTED\"}"),
            Json.parse("{\"name\":\"demo-support\"}"),
            Json.parse("{\"kind\":\"PRIMARY\",\"is_compensation\":false}"));
    StringBuilder sb = new StringBuilder("{\"order_id\":\"ORD-0001\",\"items\":[");
    for (int i = 0; sb.length() < 1000; i++) {
      sb.append(i > 0 ? "," : "")
          .append("{\"sku\":\"SKU-")
          .append(String.format("%05d", i))
          .append("\",\"qty\":")
          .append(i % 7 + 1)
          .append(",\"note\":\"réparation – café\"}");
    }
    sb.append("]}");
    args1k = Json.parse(sb.toString());
  }

  /** B4: evaluate support-default for a refund with facts. */
  @Benchmark
  public PolicyEvaluator.Result b4PolicyEvaluateRefund() {
    return PolicyEvaluator.evaluate(compiled, "shop_issue_refund", refundCtx);
  }

  /** B5: JCS canonicalisation of 1 KB args. */
  @Benchmark
  public String b5aJcs1kb() {
    return Jcs.canonical(args1k);
  }

  /** B5: args_hash (JCS + SHA-256) of 1 KB args. */
  @Benchmark
  public String b5bArgsHash1kb() {
    return Hashing.argsHash("shop_set_order_note", args1k);
  }
}
