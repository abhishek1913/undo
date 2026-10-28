package dev.undo.server.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.ObjectMappers;
import com.anthropic.core.http.HttpResponseFor;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.BadRequestException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.BetaMessageParam;
import com.anthropic.models.beta.messages.BetaOutputConfig;
import com.anthropic.models.beta.messages.BetaTool;
import com.anthropic.models.beta.messages.BetaToolChoice;
import com.anthropic.models.beta.messages.BetaToolChoiceAuto;
import com.anthropic.models.beta.messages.BetaToolUnion;
import com.anthropic.models.beta.messages.MessageCreateParams;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.undo.core.agent.ModelException;
import dev.undo.core.agent.ModelRequest;
import dev.undo.core.agent.ModelResponse;
import dev.undo.core.connector.ToolSpec;
import dev.undo.core.json.Json;
import dev.undo.core.port.ModelClient;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * The Claude adapter (Section 17.2) on the beta Messages endpoint of {@code anthropic-java}:
 * server-side refusal fallback ({@code fallbacks: "default"}), effort through {@code
 * output_config}, strict tools, {@code tool_choice: auto}, top-level automatic prompt caching.
 *
 * <p>Transcript (17.3): stored messages are converted to SDK params by deserializing the stored
 * JSON, so content blocks (thinking, fallback, tool_use) go back exactly as Claude sent them. The
 * stored response is the raw HTTP body, not a re-serialized SDK object.
 *
 * <p>Off by default: the bean exists only when {@code ANTHROPIC_API_KEY} (or a base URL for tests)
 * is set. The key never reaches logs, events or the database.
 */
public final class ClaudeModelClient implements ModelClient {
  public static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";

  /** JSON Schema keywords removed from the copy sent with {@code strict: true} (ADR-019 #4). */
  static final Set<String> STRICT_UNSUPPORTED =
      Set.of(
          "minimum",
          "maximum",
          "exclusiveMinimum",
          "exclusiveMaximum",
          "multipleOf",
          "minLength",
          "maxLength",
          "pattern",
          "format",
          "minItems",
          "maxItems",
          "$schema");

  private final AnthropicClient client;
  private final boolean fallbacks;
  private final ObjectMapper sdkJson = ObjectMappers.jsonMapper();

  public ClaudeModelClient(AnthropicClient client, boolean fallbacks) {
    this.client = client;
    this.fallbacks = fallbacks;
  }

  /** {@code baseUrl} null = the SDK default (api.anthropic.com). */
  public static ClaudeModelClient create(String apiKey, String baseUrl, boolean fallbacks) {
    AnthropicOkHttpClient.Builder b =
        AnthropicOkHttpClient.builder().apiKey(apiKey).maxRetries(2).timeout(Duration.ofMinutes(5));
    if (baseUrl != null && !baseUrl.isBlank()) {
      b.baseUrl(baseUrl);
    }
    return new ClaudeModelClient(b.build(), fallbacks);
  }

  /** The request params; package-visible for the offline tests. */
  MessageCreateParams params(ModelRequest req) {
    MessageCreateParams.Builder b =
        MessageCreateParams.builder()
            .model(req.model())
            .maxTokens(req.maxTokens())
            .addBeta(FALLBACK_BETA)
            .outputConfig(
                BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.of(req.effort())).build())
            .toolChoice(BetaToolChoice.ofAuto(BetaToolChoiceAuto.builder().build()))
            .cacheControl(BetaCacheControlEphemeral.builder().build())
            .system(req.system())
            .messages(messages(req.messages()))
            .tools(tools(req.tools()));
    if (fallbacks) {
      b.fallbacksDefault();
    }
    return b.build();
  }

  @Override
  public ModelResponse create(ModelRequest req) throws ModelException {
    MessageCreateParams params = params(req);
    try (HttpResponseFor<BetaMessage> r =
        client.beta().messages().withRawResponse().create(params)) {
      JsonNode raw = Json.parse(r.body().readAllBytes());
      JsonNode u = raw.path("usage");
      return new ModelResponse(
          raw,
          raw.path("model").asText(req.model()),
          raw.path("stop_reason").asText(""),
          u.path("input_tokens").asInt(),
          u.path("output_tokens").asInt(),
          u.path("cache_read_input_tokens").asInt(),
          u.path("cache_creation_input_tokens").asInt());
    } catch (RateLimitException e) {
      throw new ModelException(ModelException.Kind.RATE_LIMITED, "rate limited", e);
    } catch (BadRequestException e) {
      throw new ModelException(
          ModelException.Kind.BAD_REQUEST, "bad request: " + e.getMessage(), e);
    } catch (AnthropicServiceException e) {
      ModelException.Kind k =
          e.statusCode() >= 500 ? ModelException.Kind.SERVER_ERROR : ModelException.Kind.OTHER;
      throw new ModelException(k, "HTTP " + e.statusCode(), e);
    } catch (AnthropicIoException e) {
      throw new ModelException(ModelException.Kind.CONNECTION, "connection failed", e);
    } catch (IOException e) {
      throw new ModelException(ModelException.Kind.CONNECTION, "reading the response failed", e);
    }
  }

  /** Stored message JSON → SDK params, without changing a block. */
  List<BetaMessageParam> messages(List<JsonNode> stored) {
    List<BetaMessageParam> out = new ArrayList<>();
    for (JsonNode m : stored) {
      out.add(sdkJson.convertValue(m, BetaMessageParam.class));
    }
    return out;
  }

  /** Tools in the given (stable) order, strict, with a schema copy strict mode accepts. */
  List<BetaToolUnion> tools(List<ToolSpec> specs) {
    List<BetaToolUnion> out = new ArrayList<>();
    for (ToolSpec t : specs) {
      ObjectNode n = Json.obj();
      n.put("name", t.name());
      n.put("description", t.description());
      n.set("input_schema", strictCopy(t.inputSchema()));
      n.put("strict", true);
      out.add(BetaToolUnion.ofBetaTool(sdkJson.convertValue(n, BetaTool.class)));
    }
    return out;
  }

  static JsonNode strictCopy(JsonNode schema) {
    JsonNode copy = schema.deepCopy();
    strip(copy);
    return copy;
  }

  private static void strip(JsonNode n) {
    if (n.isObject()) {
      ObjectNode o = (ObjectNode) n;
      List<String> drop = new ArrayList<>();
      Iterator<String> names = o.fieldNames();
      while (names.hasNext()) {
        String f = names.next();
        if (STRICT_UNSUPPORTED.contains(f)) {
          drop.add(f);
        }
      }
      o.remove(drop);
      Iterator<java.util.Map.Entry<String, JsonNode>> it = o.fields();
      while (it.hasNext()) {
        var e = it.next();
        if (e.getKey().equals("properties") && e.getValue().isObject()) {
          e.getValue().forEach(ClaudeModelClient::strip); // keys are property names
        } else {
          strip(e.getValue());
        }
      }
    } else if (n.isArray()) {
      n.forEach(ClaudeModelClient::strip);
    }
  }
}
