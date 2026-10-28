package dev.shopfront.server;

import dev.shopfront.core.faults.FaultConfig;
import dev.shopfront.core.store.ShopTx;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Provider faults around the router (Section 12.5). Applies to POST/PUT under /v1; reads only get
 * 5xx when include_reads is set.
 */
@Component
public class FaultFilter extends OncePerRequestFilter {
  private final JdbcShopStore store;

  public FaultFilter(JdbcShopStore store) {
    this.store = store;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest req) {
    return !req.getRequestURI().startsWith("/v1/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    FaultConfig f = FaultConfig.fromJson(store.tx(ShopTx::faults));
    boolean write = req.getMethod().equals("POST") || req.getMethod().equals("PUT");
    ThreadLocalRandom r = ThreadLocalRandom.current();
    if (!write) {
      if (f.includeReads() && r.nextDouble() < f.error5xxRate()) {
        fail(res, 503);
        return;
      }
      chain.doFilter(req, res);
      return;
    }
    if (f.latencyMsMax() > 0) {
      sleep(f.latencyMsMin() + r.nextInt(Math.max(1, f.latencyMsMax() - f.latencyMsMin() + 1)));
    }
    if (r.nextDouble() < f.error5xxRate()) {
      fail(res, 503); // before handling: no effect applied
      return;
    }
    if (r.nextDouble() < f.rateLimitRate()) {
      res.setHeader("Retry-After", "1");
      fail(res, 429);
      return;
    }
    boolean drop = r.nextDouble() < f.dropAfterCommitRate();
    boolean hang = !drop && r.nextDouble() < f.hangAfterCommitRate();
    if (!drop && !hang) {
      chain.doFilter(req, res);
      return;
    }
    ContentCachingResponseWrapper wrapped = new ContentCachingResponseWrapper(res);
    chain.doFilter(req, wrapped); // handled and committed
    if (drop) {
      wrapped.resetBuffer();
      res.reset();
      res.setStatus(502); // empty body: the client cannot tell what happened
      return;
    }
    sleep(f.hangMs());
    wrapped.copyBodyToResponse();
  }

  private static void fail(HttpServletResponse res, int status) throws IOException {
    res.setStatus(status);
    res.setContentType("application/json");
    String code = status == 429 ? "rate_limited" : "service_unavailable";
    res.getWriter()
        .write(
            "{\"error\":{\"code\":\""
                + code
                + "\",\"message\":\"Injected fault.\",\"details\":{}}}");
  }

  private static void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
