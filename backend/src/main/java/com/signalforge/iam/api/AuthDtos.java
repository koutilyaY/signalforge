package com.signalforge.iam.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/** Request and response shapes for the authentication endpoints. */
public final class AuthDtos {

  private AuthDtos() {}

  public record LoginRequest(
      @NotBlank @Email @Size(max = 320) String email,
      @NotBlank @Size(min = 1, max = 200) String password) {}

  public record RefreshRequest(@NotBlank String refreshToken) {}

  /**
   * Bootstraps a brand new tenant together with its first ADMIN. This is the only endpoint that
   * creates an organization, and the only unauthenticated write in the system.
   */
  public record RegisterOrganizationRequest(
      @NotBlank @Size(max = 200) String organizationName,
      @NotBlank
          @Size(min = 2, max = 80)
          @Pattern(
              regexp = "^[a-z0-9]([a-z0-9-]*[a-z0-9])?$",
              message = "must be lowercase alphanumeric with optional hyphens")
          String organizationSlug,
      @NotBlank @Email @Size(max = 320) String adminEmail,
      @NotBlank @Size(max = 200) String adminFullName,
      /**
       * 12 characters minimum. Length beats composition rules - a 12-character passphrase has more
       * entropy than "P@ssw0rd!" and is far likelier to be remembered rather than written down.
       */
      @NotBlank @Size(min = 12, max = 200) String adminPassword) {}

  public record TokenResponse(
      String accessToken,
      String refreshToken,
      String tokenType,
      int expiresInSeconds,
      Instant expiresAt,
      CurrentUserResponse user) {

    public static TokenResponse of(
        String accessToken,
        String refreshToken,
        int expiresInSeconds,
        Instant expiresAt,
        CurrentUserResponse user) {
      return new TokenResponse(
          accessToken, refreshToken, "Bearer", expiresInSeconds, expiresAt, user);
    }
  }

  public record CurrentUserResponse(
      UUID id,
      String email,
      String fullName,
      String role,
      UUID organizationId,
      String organizationName,
      String organizationSlug) {}
}
