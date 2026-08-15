package com.signalforge.iam.auth;

import com.signalforge.iam.domain.Role;
import com.signalforge.iam.domain.User;
import com.signalforge.platform.config.SignalForgeProperties;
import com.signalforge.platform.error.ApiException;
import com.signalforge.platform.error.ErrorCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Set;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Issues and verifies HS256 access and refresh tokens.
 *
 * <p>Claims carried: subject (user id), {@code org} (organization id), {@code role}, {@code email},
 * {@code typ} (access|refresh) and {@code jti}. The organization id lives in the signed token so
 * that the tenant for a request is established by something the client cannot forge - it is never
 * read from a header, query parameter or request body.
 *
 * <p>Symmetric HS256 is the right call for a single-issuer, single-verifier system: there is no
 * third party that needs to verify without being able to sign. ADR-0004 records what would change
 * if SignalForge grew a second verifier (rotate to RS256/JWKS).
 */
@Service
public class JwtService {

  private static final Logger log = LoggerFactory.getLogger(JwtService.class);

  private static final String CLAIM_ORG = "org";
  private static final String CLAIM_ROLE = "role";
  private static final String CLAIM_EMAIL = "email";
  private static final String CLAIM_TYPE = "typ";
  private static final String TYPE_ACCESS = "access";
  private static final String TYPE_REFRESH = "refresh";

  /** 32 bytes = 256 bits, the minimum for HS256 to be meaningfully secure. */
  private static final int MIN_SECRET_BYTES = 32;

  private static final Set<String> REJECTED_SECRETS =
      Set.of(
          "changeme",
          "secret",
          "change_me_generate_with_openssl_rand_base64_48",
          "CHANGE_ME_generate_with_openssl_rand_base64_48");

  private final SecretKey signingKey;
  private final String issuer;
  private final Duration accessTtl;
  private final Duration refreshTtl;

  public JwtService(SignalForgeProperties properties) {
    SignalForgeProperties.Security security = properties.security();
    this.signingKey = buildKey(security.jwtSecret());
    this.issuer = security.jwtIssuer();
    this.accessTtl = security.accessTokenTtl();
    this.refreshTtl = security.refreshTokenTtl();
    log.info("JWT configured issuer={} accessTtl={} refreshTtl={}", issuer, accessTtl, refreshTtl);
  }

  /**
   * Refuses to start on a weak or placeholder secret. A misconfigured signing key is not something
   * to discover in production; failing fast here is the whole point.
   */
  private static SecretKey buildKey(String configured) {
    if (configured == null || configured.isBlank()) {
      throw new IllegalStateException(
          "signalforge.security.jwt-secret is not set. Generate one with: openssl rand -base64 48");
    }
    String trimmed = configured.trim();
    if (REJECTED_SECRETS.contains(trimmed)) {
      throw new IllegalStateException(
          "signalforge.security.jwt-secret is still the placeholder value. "
              + "Generate a real one with: openssl rand -base64 48");
    }

    // Accept either base64 or raw text, whichever yields more entropy.
    byte[] material;
    try {
      material = Base64.getDecoder().decode(trimmed);
      if (material.length < MIN_SECRET_BYTES) {
        material = trimmed.getBytes(StandardCharsets.UTF_8);
      }
    } catch (IllegalArgumentException notBase64) {
      material = trimmed.getBytes(StandardCharsets.UTF_8);
    }

    if (material.length < MIN_SECRET_BYTES) {
      throw new IllegalStateException(
          "signalforge.security.jwt-secret must decode to at least %d bytes (got %d). "
                  .formatted(MIN_SECRET_BYTES, material.length)
              + "Generate one with: openssl rand -base64 48");
    }
    return Keys.hmacShaKeyFor(material);
  }

  public IssuedTokens issueFor(User user) {
    Instant now = Instant.now();
    String accessToken = build(user, TYPE_ACCESS, now, accessTtl);
    String refreshToken = build(user, TYPE_REFRESH, now, refreshTtl);
    return new IssuedTokens(
        accessToken, refreshToken, now.plus(accessTtl), (int) accessTtl.toSeconds());
  }

  private String build(User user, String type, Instant issuedAt, Duration ttl) {
    return Jwts.builder()
        .issuer(issuer)
        .subject(user.getId().toString())
        .id(UUID.randomUUID().toString())
        .claim(CLAIM_ORG, user.getOrganizationId().toString())
        .claim(CLAIM_ROLE, user.getRole().name())
        .claim(CLAIM_EMAIL, user.getEmail())
        .claim(CLAIM_TYPE, type)
        .issuedAt(Date.from(issuedAt))
        .expiration(Date.from(issuedAt.plus(ttl)))
        .signWith(signingKey)
        .compact();
  }

  /** Parses and validates an access token. Throws {@link ApiException} on anything suspect. */
  public ParsedToken parseAccessToken(String token) {
    return parse(token, TYPE_ACCESS);
  }

  public ParsedToken parseRefreshToken(String token) {
    return parse(token, TYPE_REFRESH);
  }

  private ParsedToken parse(String token, String expectedType) {
    Claims claims;
    try {
      claims =
          Jwts.parser()
              .verifyWith(signingKey)
              .requireIssuer(issuer)
              .build()
              .parseSignedClaims(token)
              .getPayload();
    } catch (ExpiredJwtException e) {
      throw new ApiException(ErrorCode.TOKEN_EXPIRED, "Access token has expired");
    } catch (JwtException | IllegalArgumentException e) {
      // Deliberately vague: distinguishing "bad signature" from "malformed" helps an attacker.
      throw new ApiException(ErrorCode.TOKEN_INVALID, "Access token is not valid");
    }

    // A refresh token must never be accepted where an access token is expected.
    // Without this check the long-lived refresh token becomes a long-lived access token.
    String type = claims.get(CLAIM_TYPE, String.class);
    if (!expectedType.equals(type)) {
      throw new ApiException(ErrorCode.TOKEN_INVALID, "Wrong token type for this operation");
    }

    try {
      return new ParsedToken(
          UUID.fromString(claims.getSubject()),
          UUID.fromString(claims.get(CLAIM_ORG, String.class)),
          claims.get(CLAIM_EMAIL, String.class),
          Role.from(claims.get(CLAIM_ROLE, String.class)),
          claims.getExpiration().toInstant());
    } catch (RuntimeException e) {
      throw new ApiException(ErrorCode.TOKEN_INVALID, "Access token is missing required claims");
    }
  }

  public record IssuedTokens(
      String accessToken,
      String refreshToken,
      Instant accessTokenExpiresAt,
      int expiresInSeconds) {}

  public record ParsedToken(
      UUID userId, UUID organizationId, String email, Role role, Instant expiresAt) {}
}
