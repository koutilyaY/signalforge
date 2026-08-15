package com.signalforge.iam.api;

import com.signalforge.iam.auth.AuthenticatedPrincipal;
import com.signalforge.iam.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication")
public class AuthController {

  private final AuthService authService;

  public AuthController(AuthService authService) {
    this.authService = authService;
  }

  @PostMapping("/login")
  @Operation(summary = "Exchange email and password for an access + refresh token pair")
  public AuthDtos.TokenResponse login(@Valid @RequestBody AuthDtos.LoginRequest request) {
    return authService.login(request);
  }

  @PostMapping("/refresh")
  @Operation(summary = "Exchange a refresh token for a fresh access token")
  public AuthDtos.TokenResponse refresh(@Valid @RequestBody AuthDtos.RefreshRequest request) {
    return authService.refresh(request);
  }

  @PostMapping("/register-organization")
  @Operation(summary = "Create a new organization together with its first ADMIN user")
  public ResponseEntity<AuthDtos.TokenResponse> register(
      @Valid @RequestBody AuthDtos.RegisterOrganizationRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(authService.registerOrganization(request));
  }

  /**
   * Note the organization id comes from the authenticated principal, never from the request. This
   * is the pattern every controller in the codebase follows.
   */
  @GetMapping("/me")
  @Operation(summary = "Describe the currently authenticated user")
  public AuthDtos.CurrentUserResponse me(
      @AuthenticationPrincipal AuthenticatedPrincipal principal) {
    return authService.currentUser(principal.userId(), principal.organizationId());
  }
}
