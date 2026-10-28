package dev.undo.server.security;

import dev.undo.core.port.Clock;
import dev.undo.server.api.Problems;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Console sessions on /api/**: the JWT in the HttpOnly cookie undo_session, and for every
 * state-changing request the double-submit header X-CSRF-Token, equal to the csrf claim of the
 * session (FR-SEC-2). Agent API keys are not accepted here (FR-SEC-3).
 */
public final class ConsoleSessionFilter extends OncePerRequestFilter {
  public static final String COOKIE = "undo_session";
  public static final String CSRF_COOKIE = "undo_csrf";
  private final SessionTokens tokens;
  private final Clock clock;

  public ConsoleSessionFilter(SessionTokens tokens, Clock clock) {
    this.tokens = tokens;
    this.clock = clock;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest req) {
    return !req.getRequestURI().startsWith("/api/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    Optional<SessionTokens.Session> s = Optional.empty();
    if (req.getCookies() != null) {
      for (Cookie c : req.getCookies()) {
        if (c.getName().equals(COOKIE)) {
          s = tokens.verify(c.getValue(), clock.now());
        }
      }
    }
    if (s.isPresent()) {
      String m = req.getMethod();
      boolean changing = !(m.equals("GET") || m.equals("HEAD") || m.equals("OPTIONS"));
      if (changing && !req.getRequestURI().equals("/api/auth/login")) {
        String header = req.getHeader("X-CSRF-Token");
        if (header == null
            || !MessageDigest.isEqual(
                header.getBytes(StandardCharsets.UTF_8),
                s.get().csrf().getBytes(StandardCharsets.UTF_8))) {
          Problems.write(
              res, 403, "FORBIDDEN", "Missing or wrong X-CSRF-Token.", req.getRequestURI());
          return;
        }
      }
      ConsoleUser u = s.get().user();
      List<GrantedAuthority> auth = new ArrayList<>();
      auth.add(new SimpleGrantedAuthority("ROLE_CONSOLE"));
      u.roles().forEach(r -> auth.add(new SimpleGrantedAuthority("ROLE_" + r)));
      SecurityContextHolder.getContext()
          .setAuthentication(new UsernamePasswordAuthenticationToken(u, null, auth));
    }
    try {
      chain.doFilter(req, res);
    } finally {
      SecurityContextHolder.clearContext();
    }
  }
}
