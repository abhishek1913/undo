package dev.undo.core.json;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * RFC 8785 canonical JSON, restricted to objects, arrays, strings, integers, booleans and null
 * (Section 33.1). Keys sorted by UTF-16 code units; no whitespace; only {@code "}, {@code \} and
 * U+0000 to U+001F escaped, with short forms for b, t, n, f, r and lowercase hex escapes otherwise.
 */
public final class Jcs {
  private Jcs() {}

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
      keys.sort(null); // String.compareTo compares UTF-16 code units
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
      throw new IllegalArgumentException(
          "floating point numbers are not allowed in canonical args");
    } else {
      throw new IllegalArgumentException("unsupported JSON node type " + n.getNodeType());
    }
  }

  static void writeString(String s, StringBuilder sb) {
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
            sb.append("\\u00")
                .append(Character.forDigit(c >> 4, 16))
                .append(Character.forDigit(c & 0xF, 16));
          } else {
            sb.append(c);
          }
        }
      }
    }
    sb.append('"');
  }
}
