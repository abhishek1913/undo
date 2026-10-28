package dev.undo.server.api;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.undo.core.engine.UndoException;
import dev.undo.core.json.Json;
import dev.undo.core.policy.PolicyError;
import dev.undo.core.policy.PolicyInvalidException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** RFC 9457 application/problem+json errors with an Undo error code (Section 19). */
@RestControllerAdvice
public class Problems {
  public static ObjectNode body(int status, String code, String detail, String instance) {
    ObjectNode o = Json.obj();
    o.put("type", "https://undo.dev/errors/" + code.toLowerCase(Locale.ROOT).replace('_', '-'));
    o.put("title", title(code));
    o.put("status", status);
    o.put("code", code);
    o.put("detail", detail);
    o.put("instance", instance);
    return o;
  }

  static String title(String code) {
    String t = code.toLowerCase(Locale.ROOT).replace('_', ' ');
    return Character.toUpperCase(t.charAt(0)) + t.substring(1);
  }

  public static ResponseEntity<ObjectNode> of(
      int status, String code, String detail, String instance) {
    return ResponseEntity.status(status)
        .header("Content-Type", "application/problem+json")
        .body(body(status, code, detail, instance));
  }

  public static void write(
      HttpServletResponse res, int status, String code, String detail, String instance)
      throws IOException {
    res.setStatus(status);
    res.setContentType("application/problem+json");
    res.getOutputStream()
        .write(Json.write(body(status, code, detail, instance)).getBytes(StandardCharsets.UTF_8));
  }

  @ExceptionHandler(UndoException.class)
  ResponseEntity<ObjectNode> undo(UndoException e, HttpServletRequest req) {
    return of(e.code().http, e.code().name(), e.getMessage(), req.getRequestURI());
  }

  @ExceptionHandler(PolicyInvalidException.class)
  ResponseEntity<ObjectNode> policy(PolicyInvalidException e, HttpServletRequest req) {
    ResponseEntity<ObjectNode> r =
        of(422, "POLICY_INVALID", "The policy has errors.", req.getRequestURI());
    var arr = r.getBody().putArray("errors");
    for (PolicyError pe : e.errors()) {
      ObjectNode o = arr.addObject();
      o.put("line", pe.line());
      o.put("message", pe.message());
    }
    return r;
  }

  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class,
    MissingRequestHeaderException.class,
    IllegalArgumentException.class
  })
  ResponseEntity<ObjectNode> bad(Exception e, HttpServletRequest req) {
    return of(
        422,
        "INVALID_REQUEST",
        e.getMessage() == null ? "Invalid request." : e.getMessage().lines().findFirst().orElse(""),
        req.getRequestURI());
  }
}
