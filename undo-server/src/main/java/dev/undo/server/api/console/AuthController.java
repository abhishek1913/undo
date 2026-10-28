package dev.undo.server.api.console;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.undo.core.json.Json;
import dev.undo.core.port.Clock;
import dev.undo.server.api.Problems;
import dev.undo.server.security.ConsoleSessionFilter;
import dev.undo.server.security.ConsoleUser;
import dev.undo.server.security.SessionTokens;
import dev.undo.server.security.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** POST /api/auth/login, /logout, GET /api/auth/me. */
@RestController
@RequestMapping("/api/auth")
public class AuthController {
  private static final SecureRandom RANDOM = new SecureRandom();
  private final UserService users;
  private final SessionTokens tokens;
  private final Clock clock;

  public AuthController(UserService users, SessionTokens tokens, Clock clock) {
    this.users = users;
    this.tokens = tokens;
    this.clock = clock;
  }

  @PostMapping("/login")
  public ResponseEntity<?> login(
      @RequestBody JsonNode body, HttpServletRequest req, HttpServletResponse res) {
    Optional<ConsoleUser> u =
        users.login(body.path("email").asText(null), body.path("password").asText(null));
    if (u.isEmpty()) {
      return Problems.of(401, "UNAUTHENTICATED", "Wrong email or password.", req.getRequestURI());
    }
    byte[] b = new byte[24];
    RANDOM.nextBytes(b);
    String csrf = HexFormat.of().formatHex(b);
    String jwt = tokens.issue(u.get(), csrf, clock.now());
    boolean secure = !isLocal(req);
    res.addHeader(
        "Set-Cookie",
        cookie(ConsoleSessionFilter.COOKIE, jwt, true, secure, SessionTokens.TTL.toSeconds()));
    res.addHeader(
        "Set-Cookie",
        cookie(
            ConsoleSessionFilter.CSRF_COOKIE, csrf, false, secure, SessionTokens.TTL.toSeconds()));
    ObjectNode out = Json.obj();
    out.set("user", user(u.get()));
    out.put("csrf_token", csrf);
    return ResponseEntity.ok(out);
  }

  @PostMapping("/logout")
  public ResponseEntity<JsonNode> logout(HttpServletRequest req, HttpServletResponse res) {
    boolean secure = !isLocal(req);
    res.addHeader("Set-Cookie", cookie(ConsoleSessionFilter.COOKIE, "", true, secure, 0));
    res.addHeader("Set-Cookie", cookie(ConsoleSessionFilter.CSRF_COOKIE, "", false, secure, 0));
    return ResponseEntity.ok(Json.obj().put("signed_out", true));
  }

  @GetMapping("/me")
  public JsonNode me(@AuthenticationPrincipal ConsoleUser u, HttpServletRequest req) {
    ObjectNode o = Json.obj();
    o.set("user", user(u));
    return o;
  }

  static ObjectNode user(ConsoleUser u) {
    ObjectNode o = Json.obj();
    o.put("id", u.id().toString());
    o.put("email", u.email());
    var roles = o.putArray("roles");
    u.roles().stream().sorted().forEach(roles::add);
    return o;
  }

  static String cookie(String name, String value, boolean httpOnly, boolean secure, long maxAge) {
    return ResponseCookie.from(name, value)
        .httpOnly(httpOnly)
        .secure(secure)
        .sameSite("Strict")
        .path("/")
        .maxAge(maxAge)
        .build()
        .toString();
  }

  static boolean isLocal(HttpServletRequest req) {
    String h = req.getServerName();
    return h.equals("localhost")
        || h.equals("127.0.0.1")
        || h.equals("undo-api")
        || h.endsWith(".local");
  }
}
