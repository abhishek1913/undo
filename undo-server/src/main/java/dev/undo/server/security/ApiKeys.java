package dev.undo.server.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/** undo_sk_ + 32 random bytes in base62 (43 characters). Only SHA-256 is stored. */
public final class ApiKeys {
  private static final String ALPHABET =
      "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
  private static final SecureRandom RANDOM = new SecureRandom();

  private ApiKeys() {}

  public static String generate() {
    byte[] b = new byte[32];
    RANDOM.nextBytes(b);
    return "undo_sk_" + base62(b);
  }

  static String base62(byte[] bytes) {
    java.math.BigInteger n = new java.math.BigInteger(1, bytes);
    StringBuilder sb = new StringBuilder();
    java.math.BigInteger base = java.math.BigInteger.valueOf(62);
    while (n.signum() > 0) {
      java.math.BigInteger[] qr = n.divideAndRemainder(base);
      sb.append(ALPHABET.charAt(qr[1].intValue()));
      n = qr[0];
    }
    while (sb.length() < 43) {
      sb.append('0');
    }
    return sb.reverse().toString();
  }

  public static byte[] hash(String key) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }

  /** The first 12 characters, shown in the UI (undo_sk_AbCd). */
  public static String prefix(String key) {
    return key.substring(0, Math.min(12, key.length()));
  }

  /** Glob match for allowed_tools patterns such as shop_* (only * is special). */
  public static boolean allowed(java.util.List<String> globs, String tool) {
    for (String g : globs) {
      String re = java.util.regex.Pattern.quote(g).replace("*", "\\E.*\\Q");
      if (tool.matches(re)) {
        return true;
      }
    }
    return false;
  }
}
