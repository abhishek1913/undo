package dev.undo.core.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** One shared Jackson mapper for core. JSON values are treated as immutable once built. */
public final class Json {
  public static final ObjectMapper MAPPER =
      new ObjectMapper()
          .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
          .configure(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, false);
  private static final JsonNodeFactory F = JsonNodeFactory.instance;

  private Json() {}

  public static ObjectNode obj() {
    return F.objectNode();
  }

  public static ArrayNode arr() {
    return F.arrayNode();
  }

  public static JsonNode nul() {
    return F.nullNode();
  }

  public static JsonNode parse(String s) {
    try {
      return MAPPER.readTree(s);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("invalid JSON: " + e.getOriginalMessage(), e);
    }
  }

  public static JsonNode parse(byte[] b) {
    if (b == null || b.length == 0) {
      return F.missingNode();
    }
    try {
      return MAPPER.readTree(b);
    } catch (IOException e) {
      throw new IllegalArgumentException("invalid JSON", e);
    }
  }

  public static String write(JsonNode n) {
    try {
      return MAPPER.writeValueAsString(n);
    } catch (JsonProcessingException e) {
      throw new UncheckedIOException(e);
    }
  }

  public static byte[] bytes(JsonNode n) {
    return write(n).getBytes(StandardCharsets.UTF_8);
  }

  /** null for missing or JSON null, else the text. */
  public static String text(JsonNode n, String field) {
    JsonNode v = n == null ? null : n.get(field);
    return v == null || v.isNull() || v.isMissingNode() ? null : v.asText();
  }

  public static boolean present(JsonNode n) {
    return n != null && !n.isNull() && !n.isMissingNode();
  }
}
