package dev.undo.server.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import dev.undo.server.config.UndoProperties;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Console sessions: JWT HS256, valid 8 hours (FR-SEC-2). */
@Component
public class SessionTokens {
  public static final Duration TTL = Duration.ofHours(8);
  private final byte[] secret;

  public SessionTokens(UndoProperties props) {
    byte[] s = props.auth().jwtSecret().getBytes(StandardCharsets.UTF_8);
    if (s.length < 32) {
      throw new IllegalStateException(
          "undo.auth.jwt-secret (UNDO_JWT_SECRET) must be at least 32 bytes");
    }
    this.secret = s;
  }

  public String issue(ConsoleUser u, String csrf, Instant now) {
    try {
      JWTClaimsSet claims =
          new JWTClaimsSet.Builder()
              .subject(u.id().toString())
              .claim("email", u.email())
              .claim("roles", List.copyOf(u.roles()))
              .claim("csrf", csrf)
              .issueTime(Date.from(now))
              .expirationTime(Date.from(now.plus(TTL)))
              .build();
      SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
      jwt.sign(new MACSigner(secret));
      return jwt.serialize();
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
    }
  }

  public record Session(ConsoleUser user, String csrf) {}

  public Optional<Session> verify(String token, Instant now) {
    try {
      SignedJWT jwt = SignedJWT.parse(token);
      if (!jwt.verify(new MACVerifier(secret))) {
        return Optional.empty();
      }
      JWTClaimsSet c = jwt.getJWTClaimsSet();
      if (c.getExpirationTime() == null || c.getExpirationTime().toInstant().isBefore(now)) {
        return Optional.empty();
      }
      ConsoleUser u =
          new ConsoleUser(
              UUID.fromString(c.getSubject()),
              c.getStringClaim("email"),
              new HashSet<>(c.getStringListClaim("roles")));
      return Optional.of(new Session(u, c.getStringClaim("csrf")));
    } catch (ParseException | JOSEException | IllegalArgumentException e) {
      return Optional.empty();
    }
  }
}
