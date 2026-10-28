package dev.shopfront.server;

import dev.shopfront.core.router.ShopRequest;
import dev.shopfront.core.router.ShopResponse;
import dev.shopfront.core.router.ShopRouter;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** One catch-all controller: servlet request → ShopRequest → ShopRouter → servlet response. */
@RestController
public class ShopController {
  private final ShopRouter router;

  public ShopController(ShopRouter router) {
    this.router = router;
  }

  @RequestMapping({"/v1/**", "/admin/**"})
  public ResponseEntity<byte[]> handle(HttpServletRequest req) throws IOException {
    ShopResponse resp = router.handle(toShop(req));
    ResponseEntity.BodyBuilder b = ResponseEntity.status(resp.status());
    resp.headers().forEach(b::header);
    return b.body(resp.body());
  }

  static ShopRequest toShop(HttpServletRequest req) throws IOException {
    Map<String, String> headers = new HashMap<>();
    for (String name : Collections.list(req.getHeaderNames())) {
      headers.put(name, req.getHeader(name));
    }
    String path =
        req.getRequestURI() + (req.getQueryString() == null ? "" : "?" + req.getQueryString());
    return new ShopRequest(req.getMethod(), path, headers, req.getInputStream().readAllBytes());
  }
}
