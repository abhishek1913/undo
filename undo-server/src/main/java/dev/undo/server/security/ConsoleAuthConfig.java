package dev.undo.server.security;

import dev.undo.core.port.Clock;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.stereotype.Component;

/** Plugs the console session filter into the security chain. */
@Component
public class ConsoleAuthConfig implements SecurityConfig.ConsoleAuth {
  private final SessionTokens tokens;
  private final Clock clock;

  public ConsoleAuthConfig(SessionTokens tokens, Clock clock) {
    this.tokens = tokens;
    this.clock = clock;
  }

  @Override
  public void configure(HttpSecurity http) {
    http.addFilterBefore(
        new ConsoleSessionFilter(tokens, clock), AnonymousAuthenticationFilter.class);
  }
}
