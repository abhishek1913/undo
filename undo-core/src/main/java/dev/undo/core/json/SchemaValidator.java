package dev.undo.core.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** JSON Schema draft 2020-12 validation of tool args (FR-TOOL-2, FR-TOOL-3). */
public final class SchemaValidator {
  private static final JsonSchemaFactory FACTORY =
      JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
  private final Map<String, JsonSchema> cache = new ConcurrentHashMap<>();

  /** Returns the errors; empty means valid. */
  public List<String> validate(String toolName, JsonNode schema, JsonNode args) {
    List<String> errors = new ArrayList<>();
    if (args == null || !args.isObject()) {
      errors.add("$: must be an object");
      return errors;
    }
    findFloats(args, "$", errors);
    JsonSchema compiled = cache.computeIfAbsent(toolName, k -> FACTORY.getSchema(schema));
    Set<ValidationMessage> messages = compiled.validate(args);
    for (ValidationMessage m : messages) {
      errors.add(m.getMessage());
    }
    errors.sort(null);
    return errors;
  }

  /** Money is integer minor units; floats anywhere are rejected, even 1.0. */
  private static void findFloats(JsonNode n, String path, List<String> errors) {
    if (n.isFloatingPointNumber() || n.isBigDecimal()) {
      errors.add(path + ": must be an integer, not a decimal number");
    } else if (n.isObject()) {
      for (Iterator<Map.Entry<String, JsonNode>> it = n.fields(); it.hasNext(); ) {
        Map.Entry<String, JsonNode> e = it.next();
        findFloats(e.getValue(), path + "." + e.getKey(), errors);
      }
    } else if (n.isArray()) {
      for (int i = 0; i < n.size(); i++) {
        findFloats(n.get(i), path + "[" + i + "]", errors);
      }
    }
  }
}
