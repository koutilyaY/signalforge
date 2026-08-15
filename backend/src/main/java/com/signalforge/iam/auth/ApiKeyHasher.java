package com.signalforge.iam.auth;

import com.signalforge.iam.domain.ApiKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** Generation and hashing of ingestion API keys. */
public final class ApiKeyHasher {

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final int KEY_BYTES = 32; // 256 bits

  private ApiKeyHasher() {}

  /**
   * Mints a new key: {@code sfk_<43 chars of url-safe base64>}. Returned once and never stored in
   * plaintext.
   */
  public static String generate() {
    byte[] material = new byte[KEY_BYTES];
    RANDOM.nextBytes(material);
    return ApiKey.KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(material);
  }

  /** SHA-256, hex encoded lowercase. See {@link ApiKey} for why this is not bcrypt. */
  public static String hash(String plaintextKey) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hashed = digest.digest(plaintextKey.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hashed);
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 is mandated by the JDK spec; this cannot happen.
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  /** Display prefix stored alongside the hash so operators can tell keys apart in a list. */
  public static String displayPrefix(String plaintextKey) {
    int end = Math.min(plaintextKey.length(), ApiKey.KEY_PREFIX.length() + 6);
    return plaintextKey.substring(0, end);
  }
}
