package dev.undo.server.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.sun.net.httpserver.HttpServer;
import dev.undo.connector.shopfront.ShopfrontConnector;
import dev.undo.core.agent.ModelException;
import dev.undo.core.agent.ModelRequest;
import dev.undo.core.agent.ModelResponse;
import dev.undo.core.agent.SupportAgent;
import dev.undo.core.agent.TicketScript;
import dev.undo.core.agent.Transcript;
import dev.undo.core.connector.ToolSpec;
import dev.undo.core.connector.Visibility;
import dev.undo.core.json.Json;
import dev.undo.core.model.LlmTurn;
import dev.undo.core.model.Task;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The Claude adapter offline (ADR-019): a local stub Messages API serves fixtures and records every
 * request. No network, no API key.
 */
class ClaudeModelClientTest {
  HttpServer stub;
  final List<JsonNode> requests = new ArrayList<>();
  final Map<String, String> headers = new ConcurrentHashMap<>();
  final List<String> replies = new ArrayList<>();
  int status = 200;

  static String fixture(String name) throws IOException {
    try (InputStream in =
        ClaudeModelClientTest.class.getResourceAsStream("/fixtures/claude/" + name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
    }
  }

  @BeforeEach
  void start() throws IOException {
    stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    stub.createContext(
        "/v1/messages",
        ex -> {
          JsonNode body = Json.parse(ex.getRequestBody().readAllBytes());
          String reply;
          int code;
          synchronized (this) {
            requests.add(body);
            ex.getRequestHeaders()
                .forEach((k, v) -> headers.put(k.toLowerCase(), String.join(",", v)));
            code = status;
            reply = replies.isEmpty() ? "{}" : replies.remove(0);
            // Like the real API: edited history (a changed thinking block) is a 400.
            if (body.path("messages").size() > 1 && !historyKept(body)) {
              code = 400;
              reply =
                  "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"thinking blocks were modified\"}}";
            }
          }
          byte[] b = reply.getBytes(StandardCharsets.UTF_8);
          ex.getResponseHeaders().add("Content-Type", "application/json");
          ex.sendResponseHeaders(code, b.length);
          ex.getResponseBody().write(b);
          ex.close();
        });
    stub.start();
  }

  @AfterEach
  void stop() {
    stub.stop(0);
  }

  private boolean historyKept(JsonNode second) {
    try {
      JsonNode first = Json.parse(fixture("turn1.json"));
      return second.at("/messages/1/content").equals(first.get("content"));
    } catch (IOException e) {
      return false;
    }
  }

  private ClaudeModelClient client() {
    return ClaudeModelClient.create(
        "sk-test-not-a-real-key", "http://127.0.0.1:" + stub.getAddress().getPort(), true);
  }

  static List<ToolSpec> tools() {
    ShopfrontConnector c =
        new ShopfrontConnector(
            r -> null,
            new ShopfrontConnector.Config("k", "s", Duration.ofMinutes(15), Duration.ofSeconds(3)),
            Instant::now);
    return c.tools().stream()
        .filter(t -> t.visibility() == Visibility.AGENT)
        .sorted(Comparator.comparing(ToolSpec::name))
        .toList();
  }

  private static Task task() {
    Task t = new Task();
    t.seq = 1;
    t.input = SupportAgent.ticketTask("TKT-0001", TicketScript.Knobs.PLAIN);
    return t;
  }

  private static ModelRequest request(Task t, List<JsonNode> messages) {
    return new ModelRequest(
        "claude-opus-5-5",
        SupportAgent.SYSTEM_PROMPT,
        tools(),
        messages,
        16000,
        "medium",
        t.input,
        t.seq);
  }

  /** Transcript round trip (17.3): store the response, rebuild, send the next turn, no 400. */
  @Test
  void transcriptRoundTripSendsStoredContentUnchanged() throws Exception {
    replies.add(fixture("turn1.json"));
    replies.add(fixture("turn2.json"));
    ClaudeModelClient claude = client();
    Task t = task();

    ModelResponse r1 = claude.create(request(t, Transcript.rebuild(t, List.of())));
    assertThat(r1.raw()).isEqualTo(Json.parse(fixture("turn1.json"))); // the raw body is stored
    assertThat(r1.stopReason()).isEqualTo("tool_use");
    assertThat(r1.cacheWriteTokens()).isEqualTo(2810);

    LlmTurn turn = new LlmTurn();
    turn.turnNo = 1;
    turn.response = Json.parse(Json.write(r1.raw())); // as read back from llm_turns.response
    turn.stopReason = r1.stopReason();
    ArrayNode results = Json.arr();
    results.add(
        Transcript.toolResult(
            "toolu_01FixtureGetTicket",
            Json.parse("{\"status\":\"succeeded\",\"result\":{}}"),
            false));
    turn.toolResults = results;

    ModelResponse r2 = claude.create(request(t, Transcript.rebuild(t, List.of(turn))));
    assertThat(r2.stopReason()).isEqualTo("end_turn");
    assertThat(r2.cacheReadTokens()).isEqualTo(2810);

    JsonNode second = requests.get(1);
    assertThat(second.at("/messages/0/content").asText())
        .isEqualTo(SupportAgent.ticketMessage("TKT-0001"));
    assertThat(second.at("/messages/1/role").asText()).isEqualTo("assistant");
    assertThat(second.at("/messages/1/content"))
        .isEqualTo(Json.parse(fixture("turn1.json")).get("content"));
    assertThat(second.at("/messages/2/content")).isEqualTo(results);
    // The stable prefix is byte-identical between the two requests (prompt cache).
    assertThat(Json.write(second.get("tools"))).isEqualTo(Json.write(requests.get(0).get("tools")));
    assertThat(Json.write(second.get("system")))
        .isEqualTo(Json.write(requests.get(0).get("system")));
  }

  @Test
  void requestCarriesFallbackEffortStrictToolsAutoChoiceAndCaching() throws Exception {
    replies.add(fixture("turn2.json"));
    Task t = task();
    client().create(request(t, Transcript.rebuild(t, List.of())));
    JsonNode body = requests.get(0);
    assertThat(headers.get("anthropic-beta")).contains(ClaudeModelClient.FALLBACK_BETA);
    assertThat(headers.get("x-api-key")).isEqualTo("sk-test-not-a-real-key");
    assertThat(body.path("model").asText()).isEqualTo("claude-opus-5-5");
    assertThat(body.path("max_tokens").asInt()).isEqualTo(16000);
    assertThat(body.path("fallbacks").asText()).isEqualTo("default");
    assertThat(body.at("/output_config/effort").asText()).isEqualTo("medium");
    assertThat(body.at("/tool_choice/type").asText()).isEqualTo("auto");
    assertThat(body.at("/cache_control/type").asText()).isEqualTo("ephemeral");
    assertThat(body.has("thinking")).isFalse();
    assertThat(
            body.path("system").isTextual()
                ? body.path("system").asText()
                : body.at("/system/0/text").asText())
        .isEqualTo(SupportAgent.SYSTEM_PROMPT);
    assertThat(body.path("tools")).hasSize(tools().size());
    for (JsonNode tool : body.path("tools")) {
      assertThat(tool.path("strict").asBoolean()).isTrue();
      assertThat(Json.write(tool.path("input_schema")))
          .doesNotContain("\"maximum\"", "\"minimum\"", "\"maxLength\"");
    }
    // Property names that look like keywords survive; types and required lists stay.
    JsonNode refund =
        java.util.stream.StreamSupport.stream(body.path("tools").spliterator(), false)
            .filter(x -> x.path("name").asText().equals("shop_issue_refund"))
            .findFirst()
            .orElseThrow();
    assertThat(refund.at("/input_schema/properties/amount_minor/type").asText())
        .isEqualTo("integer");
    assertThat(refund.at("/input_schema/required")).isNotEmpty();
  }

  @Test
  void errorsMapToModelExceptionKinds() throws Exception {
    status = 429;
    replies.add(
        "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"slow down\"}}");
    replies.add(replies.get(0));
    replies.add(replies.get(0));
    Task t = task();
    ModelRequest req = request(t, Transcript.rebuild(t, List.of()));
    assertThatThrownBy(() -> client().create(req))
        .isInstanceOfSatisfying(
            ModelException.class,
            e -> {
              assertThat(e.kind()).isEqualTo(ModelException.Kind.RATE_LIMITED);
              assertThat(e.transientFailure()).isTrue();
            });
    status = 400;
    replies.clear();
    replies.add(
        "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"bad\"}}");
    assertThatThrownBy(() -> client().create(req))
        .isInstanceOfSatisfying(
            ModelException.class,
            e -> assertThat(e.kind()).isEqualTo(ModelException.Kind.BAD_REQUEST));
  }
}
