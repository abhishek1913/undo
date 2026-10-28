package dev.undo.core.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.StringReader;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;

/** Parses policy YAML into JSON while remembering the line of every value (1-based). */
final class PolicyYaml {
  private static final JsonNodeFactory F = JsonNodeFactory.instance;

  record Parsed(JsonNode root, Map<String, Integer> lines, List<PolicyError> errors) {
    int line(String pointer) {
      String p = pointer;
      while (true) {
        Integer l = lines.get(p);
        if (l != null) {
          return l;
        }
        int slash = p.lastIndexOf('/');
        if (slash <= 0) {
          return lines.getOrDefault("", 1);
        }
        p = p.substring(0, slash);
      }
    }
  }

  private PolicyYaml() {}

  static Parsed parse(String yaml) {
    Map<String, Integer> lines = new HashMap<>();
    List<PolicyError> errors = new java.util.ArrayList<>();
    Node node;
    try {
      LoaderOptions opts = new LoaderOptions();
      opts.setAllowDuplicateKeys(true); // reported below with a line number
      opts.setCodePointLimit(1_000_000);
      node = new Yaml(opts).compose(new StringReader(yaml));
    } catch (RuntimeException e) {
      String msg = e.getMessage() == null ? e.toString() : e.getMessage();
      int line = 1;
      if (e instanceof org.yaml.snakeyaml.error.MarkedYAMLException m
          && m.getProblemMark() != null) {
        line = m.getProblemMark().getLine() + 1;
      }
      errors.add(
          new PolicyError(line, "YAML syntax error: " + msg.lines().findFirst().orElse(msg)));
      return new Parsed(F.nullNode(), lines, errors);
    }
    if (node == null) {
      errors.add(new PolicyError(1, "The document is empty."));
      return new Parsed(F.nullNode(), lines, errors);
    }
    JsonNode root = convert(node, "", lines, errors);
    return new Parsed(root, lines, errors);
  }

  private static JsonNode convert(
      Node n, String ptr, Map<String, Integer> lines, List<PolicyError> errors) {
    lines.put(ptr, n.getStartMark().getLine() + 1);
    if (n instanceof MappingNode m) {
      ObjectNode o = F.objectNode();
      for (NodeTuple t : m.getValue()) {
        if (!(t.getKeyNode() instanceof ScalarNode k)) {
          errors.add(
              new PolicyError(
                  t.getKeyNode().getStartMark().getLine() + 1, "Keys must be plain text."));
          continue;
        }
        String key = k.getValue();
        String childPtr = ptr + "/" + key.replace("~", "~0").replace("/", "~1");
        if (o.has(key)) {
          errors.add(
              new PolicyError(k.getStartMark().getLine() + 1, "Duplicate key '" + key + "'."));
        }
        o.set(key, convert(t.getValueNode(), childPtr, lines, errors));
      }
      return o;
    }
    if (n instanceof SequenceNode s) {
      ArrayNode a = F.arrayNode();
      int i = 0;
      for (Node c : s.getValue()) {
        a.add(convert(c, ptr + "/" + i++, lines, errors));
      }
      return a;
    }
    ScalarNode sc = (ScalarNode) n;
    String v = sc.getValue();
    Tag tag = sc.getTag();
    if (Tag.NULL.equals(tag)) {
      return F.nullNode();
    }
    if (Tag.BOOL.equals(tag)) {
      return F.booleanNode(
          v.equalsIgnoreCase("true") || v.equalsIgnoreCase("yes") || v.equalsIgnoreCase("on"));
    }
    if (Tag.INT.equals(tag)) {
      try {
        return F.numberNode(new BigInteger(v.replace("_", "")).longValueExact());
      } catch (ArithmeticException | NumberFormatException e) {
        errors.add(new PolicyError(sc.getStartMark().getLine() + 1, "Integer out of range: " + v));
        return F.nullNode();
      }
    }
    if (Tag.FLOAT.equals(tag)) {
      try {
        return F.numberNode(Double.parseDouble(v));
      } catch (NumberFormatException e) {
        return F.textNode(v);
      }
    }
    return F.textNode(v);
  }
}
