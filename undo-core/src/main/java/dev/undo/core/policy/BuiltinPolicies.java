package dev.undo.core.policy;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Policies shipped in the repo (policies/*.yaml), available on the classpath. */
public final class BuiltinPolicies {
  public static final String SUPPORT_DEFAULT = "support-default";

  private BuiltinPolicies() {}

  public static String supportDefaultYaml() {
    return load("policies/support-default.yaml");
  }

  static String load(String path) {
    try (InputStream in = BuiltinPolicies.class.getClassLoader().getResourceAsStream(path)) {
      if (in == null) {
        throw new IllegalStateException("missing resource " + path);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
