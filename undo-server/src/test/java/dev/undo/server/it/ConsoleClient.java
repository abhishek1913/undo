package dev.undo.server.it;

import dev.undo.core.json.Json;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** A browser-like console client: cookie jar plus the CSRF header from the login answer. */
final class ConsoleClient {
  final CookieManager jar = new CookieManager();
  final HttpClient client = HttpClient.newBuilder().cookieHandler(jar).build();
  String csrf;

  static ConsoleClient as(String email, String password) throws Exception {
    ConsoleClient c = new ConsoleClient();
    if (c.login(email, password).status() != 200) {
      throw new AssertionError("login failed for " + email);
    }
    return c;
  }

  StackTestSupport.Resp login(String email, String password) throws Exception {
    StackTestSupport.Resp r =
        call(
            "POST",
            "/api/auth/login",
            "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}",
            false);
    if (r.status() == 200) {
      csrf = r.body().get("csrf_token").asText();
    }
    return r;
  }

  StackTestSupport.Resp call(String method, String path, String body, boolean withCsrf)
      throws Exception {
    HttpRequest.Builder b =
        HttpRequest.newBuilder(URI.create("http://localhost:" + StackTestSupport.UNDO_PORT + path))
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body))
            .header("Content-Type", "application/json");
    if (withCsrf && csrf != null) {
      b.header("X-CSRF-Token", csrf);
    }
    HttpResponse<String> r = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    return new StackTestSupport.Resp(
        r.statusCode(), r.body().isEmpty() ? Json.obj() : Json.parse(r.body()), r.headers());
  }

  StackTestSupport.Resp get(String path) throws Exception {
    return call("GET", path, null, false);
  }

  StackTestSupport.Resp post(String path, String body) throws Exception {
    return call("POST", path, body, true);
  }
}
