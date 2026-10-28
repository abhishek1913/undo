package dev.shopfront.server;

import dev.shopfront.core.router.ShopRequest;
import dev.shopfront.core.router.ShopResponse;
import dev.shopfront.core.router.ShopRouter;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Backend for the provider page (Section 12.9). The page is a local demo tool, so these two calls
 * use the admin token on the server side; disable with SHOP_PAGE_ENABLED=false.
 */
@RestController
public class PageController {
  private final ShopRouter router;
  private final ShopProperties props;

  public PageController(ShopRouter router, ShopProperties props) {
    this.router = router;
    this.props = props;
  }

  @GetMapping("/page/data")
  public ResponseEntity<byte[]> data() {
    return forward("GET", "/admin/page-data", null);
  }

  @PutMapping("/page/orders/{id}/note")
  public ResponseEntity<byte[]> editNote(@PathVariable String id, @RequestBody String body) {
    return forward("PUT", "/admin/orders/" + id + "/note", body);
  }

  private ResponseEntity<byte[]> forward(String method, String path, String body) {
    if (!props.pageEnabled()) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }
    ShopResponse r =
        router.handle(
            ShopRequest.of(method, path, Map.of("X-Admin-Token", props.adminToken()), body));
    return ResponseEntity.status(r.status())
        .header("Content-Type", "application/json")
        .body(r.body());
  }
}
