package dev.undo.server.security;

import dev.undo.server.api.Problems;
import dev.undo.server.config.UndoProperties;
import dev.undo.server.store.ApiKeyStore;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

/**
 * Agent keys reach only /v1/** and /mcp; console sessions only /api/** (FR-SEC-3). Webhooks are
 * verified by HMAC in the handler. Everything else is closed.
 */
@Configuration
public class SecurityConfig {
  @Bean
  SecurityFilterChain filterChain(
      HttpSecurity http, ApiKeyStore keys, UndoProperties props, List<ConsoleAuth> console)
      throws Exception {
    http.csrf(c -> c.disable())
        .httpBasic(b -> b.disable())
        .formLogin(f -> f.disable())
        .logout(l -> l.disable())
        .requestCache(r -> r.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .addFilterBefore(new ApiKeyFilter(keys, props), AnonymousAuthenticationFilter.class)
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                        (req, res, ex) ->
                            Problems.write(
                                res, 401, "UNAUTHENTICATED", "Sign in first.", req.getRequestURI()))
                    .accessDeniedHandler(
                        (req, res, ex) ->
                            Problems.write(
                                res,
                                403,
                                "FORBIDDEN",
                                "Your role does not allow this.",
                                req.getRequestURI())));
    for (ConsoleAuth c : console) {
      c.configure(http);
    }
    http.authorizeHttpRequests(
        a ->
            a.requestMatchers(
                    "/actuator/health/**",
                    "/actuator/health",
                    "/actuator/prometheus",
                    "/actuator/info")
                .permitAll()
                .requestMatchers("/webhooks/**", "/error")
                .permitAll()
                .requestMatchers("/internal/**")
                .permitAll()
                .requestMatchers("/v1/**", "/mcp", "/mcp/**")
                .hasRole("AGENT")
                .requestMatchers("/api/auth/login")
                .permitAll()
                .requestMatchers("/api/**")
                .hasRole("CONSOLE")
                .anyRequest()
                .denyAll());
    return http.build();
  }

  /** Hook for the console session filter (added with the console in M5). */
  public interface ConsoleAuth {
    void configure(HttpSecurity http) throws Exception;
  }
}
