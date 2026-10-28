package dev.shopfront.core.json;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;

/** JSON helpers for Shopfront: one mapper, RFC 8785 canonical form (restricted), SHA-256. */
public final class Json {
  public static final ObjectMapper MAPPER =
      new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
  private static final JsonNodeFactory F = JsonNodeFactory.instance;

  private Json() {}

  public static ObjectNode obj() {
    return F.objectNode();
  }

  public static ArrayNode arr() {
    return F.arrayNode();
  }

  public static JsonNode parse(byte[] body) {
    if (body == null || body.length == 0) {
      return F.nullNode();
    }
    try {
      return MAPPER.readTree(body);
    } catch (IOException e) {
      throw new IllegalArgumentException("invalid JSON", e);
    }
  }

  public static byte[] bytes(JsonNode node) {
    try {
      return MAPPER.writeValueAsBytes(node);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public static String string(JsonNode node) {
    return new String(bytes(node), StandardCharsets.UTF_8);
  }

  /** RFC 8785 for objects, arrays, strings, integers, booleans and null (Section 33.1). */
  public static String canonical(JsonNode node) {
    StringBuilder sb = new StringBuilder();
    write(node, sb);
    return sb.toString();
  }

  private static void write(JsonNode n, StringBuilder sb) {
    if (n == null || n.isNull() || n.isMissingNode()) {
      sb.append("null");
    } else if (n.isObject()) {
      List<String> keys = new ArrayList<>();
      for (Iterator<String> it = n.fieldNames(); it.hasNext(); ) {
        keys.add(it.next());
      }
      keys.sort(null); // String.compareTo orders by UTF-16 code units, as RFC 8785 requires
      sb.append('{');
      for (int i = 0; i < keys.size(); i++) {
        if (i > 0) {
          sb.append(',');
        }
        writeString(keys.get(i), sb);
        sb.append(':');
        write(n.get(keys.get(i)), sb);
      }
      sb.append('}');
    } else if (n.isArray()) {
      sb.append('[');
      for (int i = 0; i < n.size(); i++) {
        if (i > 0) {
          sb.append(',');
        }
        write(n.get(i), sb);
      }
      sb.append(']');
    } else if (n.isTextual()) {
      writeString(n.textValue(), sb);
    } else if (n.isBoolean()) {
      sb.append(n.booleanValue());
    } else if (n.isIntegralNumber()) {
      sb.append(n.bigIntegerValue().toString());
    } else if (n.isNumber()) {
      throw new IllegalArgumentException("floating point numbers are not supported");
    } else {
      throw new IllegalArgumentException("unsupported JSON node: " + n.getNodeType());
    }
  }

  private static void writeString(String s, StringBuilder sb) {
    sb.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\b' -> sb.append("\\b");
        case '\t' -> sb.append("\\t");
        case '\n' -> sb.append("\\n");
        case '\f' -> sb.append("\\f");
        case '\r' -> sb.append("\\r");
        default -> {
          if (c < 0x20) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
        }
      }
    }
    sb.append('"');
  }

  public static String sha256Hex(String s) {
    return sha256Hex(s.getBytes(StandardCharsets.UTF_8));
  }

  public static String sha256Hex(byte[] b) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }
}
