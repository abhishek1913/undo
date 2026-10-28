package dev.undo.server.security;

import dev.undo.server.api.Problems;
import dev.undo.server.config.UndoProperties;
import dev.undo.server.store.ApiKeyStore;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Bearer agent API keys on /v1/** and /mcp (FR-SEC-3), with a per-key token bucket of 50 req/s,
 * burst 100, in memory per node (FR-SEC-4).
 */
public final class ApiKeyFilter extends OncePerRequestFilter {
  private final ApiKeyStore keys;
  private final UndoProperties props;
  private final ConcurrentHashMap<UUID, Bucket> buckets = new ConcurrentHashMap<>();

  public ApiKeyFilter(ApiKeyStore keys, UndoProperties props) {
    this.keys = keys;
    this.props = props;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest req) {
    String p = req.getRequestURI();
    return !(p.startsWith("/v1/") || p.equals("/v1") || p.equals("/mcp") || p.startsWith("/mcp/"));
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    String auth = req.getHeader("Authorization");
    if (auth == null || !auth.startsWith("Bearer undo_sk_")) {
      Problems.write(
          res, 401, "UNAUTHENTICATED", "Send Authorization: Bearer undo_sk_…", req.getRequestURI());
      return;
    }
    Optional<ApiKeyStore.KeyRow> row =
        keys.byHash(ApiKeys.hash(auth.substring("Bearer ".length()).trim()));
    if (row.isEmpty()) {
      Problems.write(
          res, 401, "UNAUTHENTICATED", "Unknown or revoked API key.", req.getRequestURI());
      return;
    }
    ApiKeyStore.KeyRow k = row.get();
    Bucket b = buckets.computeIfAbsent(k.id(), id -> newBucket());
    if (!b.tryConsume(1)) {
      res.setHeader("Retry-After", "1");
      Problems.write(
          res, 429, "RATE_LIMITED", "Too many requests for this API key.", req.getRequestURI());
      return;
    }
    AgentPrincipal principal =
        new AgentPrincipal(k.id(), k.agentId(), k.agentName(), k.allowedTools(), k.defaultPolicy());
    UsernamePasswordAuthenticationToken token =
        new UsernamePasswordAuthenticationToken(
            principal, null, List.of(new SimpleGrantedAuthority("ROLE_AGENT")));
    SecurityContextHolder.getContext().setAuthentication(token);
    try {
      chain.doFilter(req, res);
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  private Bucket newBucket() {
    long rps = Math.max(1, Math.round(props.ratelimit().perKeyRps()));
    return Bucket.builder()
        .addLimit(
            Bandwidth.builder()
                .capacity(props.ratelimit().burst())
                .refillGreedy(rps, Duration.ofSeconds(1))
                .build())
        .build();
  }
}
