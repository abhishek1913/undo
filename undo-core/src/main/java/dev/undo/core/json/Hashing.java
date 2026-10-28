package dev.undo.core.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class Hashing {
  private Hashing() {}

  /** args_hash = lowercase hex SHA-256 of UTF-8 JCS({"tool": name, "args": args}). */
  public static String argsHash(String tool, JsonNode args) {
    ObjectNode o = Json.obj();
    o.put("tool", tool);
    o.set("args", args);
    return sha256Hex(Jcs.canonical(o));
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
