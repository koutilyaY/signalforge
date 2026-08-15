package com.signalforge.platform.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.signalforge.iam.auth.ApiKeyAuthenticationFilter;
import com.signalforge.iam.auth.JwtAuthenticationFilter;
import com.signalforge.platform.error.ApiErrorResponse;
import com.signalforge.platform.error.ErrorCode;
import com.signalforge.platform.web.CorrelationIdFilter;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * The authorization model.
 *
 * <p>Two orthogonal layers, and both must pass:
 *
 * <ol>
 *   <li><b>Coarse, here.</b> Path-level rules that answer "is this endpoint reachable at all by
 *       this role". Cheap, and it fails closed - {@code anyRequest().authenticated()} means a new
 *       controller added tomorrow is protected by default rather than public by default.
 *   <li><b>Fine, at the method.</b> {@code @PreAuthorize} on services for role checks, plus tenant
 *       scoping inside every query. Path rules cannot express "this incident belongs to your
 *       organization"; only the query can.
 * </ol>
 *
 * <p>Sessions are stateless. CSRF is therefore disabled, which is only safe <em>because</em> the
 * credential is a bearer token in a header rather than a cookie the browser attaches automatically.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

  private final SignalForgeProperties properties;

  public SecurityConfig(SignalForgeProperties properties) {
    this.properties = properties;
  }

  @Bean
  public SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      JwtAuthenticationFilter jwtFilter,
      ApiKeyAuthenticationFilter apiKeyFilter,
      ObjectMapper objectMapper)
      throws Exception {

    http.csrf(AbstractHttpConfigurer::disable)
        .cors(cors -> cors.configurationSource(corsConfigurationSource()))
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .headers(
            headers ->
                headers
                    .frameOptions(frame -> frame.deny())
                    .contentTypeOptions(opt -> {})
                    .httpStrictTransportSecurity(
                        hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31536000))
                    .referrerPolicy(
                        r ->
                            r.policy(
                                org.springframework.security.web.header.writers
                                    .ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
        .authorizeHttpRequests(
            auth ->
                auth
                    // --- public ---
                    .requestMatchers(
                        "/api/v1/auth/login",
                        "/api/v1/auth/refresh",
                        "/api/v1/auth/register-organization")
                    .permitAll()
                    // Liveness/readiness must answer before auth is possible.
                    .requestMatchers("/actuator/health/**", "/actuator/info")
                    .permitAll()
                    // Prometheus scrapes this from inside the compose network. In a
                    // real deployment this would be bound to an internal interface or
                    // put behind network policy rather than left open.
                    .requestMatchers("/actuator/prometheus")
                    .permitAll()
                    .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                    .permitAll()
                    .requestMatchers(org.springframework.http.HttpMethod.OPTIONS, "/**")
                    .permitAll()

                    // --- ingestion: API key or ENGINEER+ ---
                    .requestMatchers("/api/v1/ingest/**")
                    .hasRole("ENGINEER")

                    // --- organization administration ---
                    .requestMatchers(
                        "/api/v1/organization/**", "/api/v1/users/**", "/api/v1/api-keys/**")
                    .hasRole("ADMIN")
                    .requestMatchers("/api/v1/audit/**")
                    .hasRole("ADMIN")

                    // --- everything else needs at least a viewer ---
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(
            ex ->
                ex.authenticationEntryPoint(
                        (request, response, authException) ->
                            write(
                                objectMapper,
                                response,
                                request.getRequestURI(),
                                ErrorCode.AUTHENTICATION_REQUIRED))
                    .accessDeniedHandler(
                        (request, response, denied) ->
                            write(
                                objectMapper,
                                response,
                                request.getRequestURI(),
                                ErrorCode.ACCESS_DENIED)));

    // API key first: the ingestion hot path should not attempt a JWT parse.
    http.addFilterBefore(apiKeyFilter, UsernamePasswordAuthenticationFilter.class);
    http.addFilterAfter(jwtFilter, ApiKeyAuthenticationFilter.class);

    return http.build();
  }

  private static void write(
      ObjectMapper mapper,
      jakarta.servlet.http.HttpServletResponse response,
      String path,
      ErrorCode code)
      throws java.io.IOException {
    response.setStatus(code.status().value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    mapper.writeValue(
        response.getOutputStream(),
        ApiErrorResponse.of(
            code, code.defaultMessage(), CorrelationIdFilter.currentCorrelationId(), path));
  }

  @Bean
  public PasswordEncoder passwordEncoder() {
    // Cost factor is configurable so tests can drop it - bcrypt at strength 12
    // is ~250ms per hash, which would add minutes to a suite that creates users.
    return new BCryptPasswordEncoder(properties.security().bcryptStrength());
  }

  @Bean
  public CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration config = new CorsConfiguration();
    List<String> allowed = properties.security().allowedOrigins();
    // Explicit origins only. Never "*" together with credentials.
    config.setAllowedOrigins(allowed == null || allowed.isEmpty() ? List.of() : allowed);
    config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    config.setAllowedHeaders(
        List.of("Authorization", "Content-Type", "X-API-Key", CorrelationIdFilter.HEADER));
    config.setExposedHeaders(List.of(CorrelationIdFilter.HEADER));
    config.setAllowCredentials(true);
    config.setMaxAge(3600L);

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/api/**", config);
    return source;
  }
}
