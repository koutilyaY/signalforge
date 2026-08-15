package com.signalforge.platform.tenant;

import com.signalforge.platform.error.ApiException;
import com.signalforge.platform.error.ErrorCode;
import java.util.Optional;
import java.util.UUID;

/**
 * Ambient tenant + principal for the current thread.
 *
 * <p>This is a convenience for cross-cutting concerns (cache keys, audit records, log MDC, metrics
 * tags). It is deliberately <em>not</em> the enforcement mechanism for tenant isolation.
 *
 * <p>Isolation is enforced in three independent places, because a ThreadLocal is exactly the kind
 * of thing that silently fails to propagate into an async executor or a Kafka listener:
 *
 * <ol>
 *   <li><b>Query level.</b> Every repository method that touches a tenant-scoped table takes {@code
 *       organizationId} as an explicit argument and includes it in the WHERE clause. There is no
 *       repository method that reads a tenant-scoped row by id alone.
 *   <li><b>Service level.</b> Controllers read the organization id from the authenticated
 *       principal, never from a request parameter, path variable or header.
 *   <li><b>Database level.</b> PostgreSQL row-level security policies (see {@code
 *       V3__row_level_security.sql}) scope every statement to {@code
 *       current_setting('signalforge.current_org')}. The runtime connects as a role that does not
 *       own the tables, so it cannot bypass the policies even if application code is wrong.
 * </ol>
 *
 * <p>Cross-tenant negative tests live in {@code TenantIsolationIT} and cover all three layers.
 */
public final class TenantContext {

  private static final ThreadLocal<TenantPrincipal> CURRENT = new ThreadLocal<>();

  private TenantContext() {}

  public static void set(TenantPrincipal principal) {
    CURRENT.set(principal);
  }

  public static void clear() {
    CURRENT.remove();
  }

  public static Optional<TenantPrincipal> current() {
    return Optional.ofNullable(CURRENT.get());
  }

  /** The current organization, or an authentication error if there is no principal bound. */
  public static UUID requireOrganizationId() {
    TenantPrincipal principal = CURRENT.get();
    if (principal == null) {
      throw new ApiException(
          ErrorCode.AUTHENTICATION_REQUIRED, "No tenant bound to the current thread");
    }
    return principal.organizationId();
  }

  public static UUID requireUserId() {
    TenantPrincipal principal = CURRENT.get();
    if (principal == null) {
      throw new ApiException(
          ErrorCode.AUTHENTICATION_REQUIRED, "No principal bound to the current thread");
    }
    return principal.userId();
  }

  /**
   * Runs {@code action} with the given principal bound, restoring whatever was previously bound.
   * Used by Kafka listeners and scheduled jobs, which have no HTTP request to inherit from.
   */
  public static <T> T callAs(TenantPrincipal principal, java.util.function.Supplier<T> action) {
    TenantPrincipal previous = CURRENT.get();
    CURRENT.set(principal);
    try {
      return action.get();
    } finally {
      if (previous == null) {
        CURRENT.remove();
      } else {
        CURRENT.set(previous);
      }
    }
  }

  public static void runAs(TenantPrincipal principal, Runnable action) {
    callAs(
        principal,
        () -> {
          action.run();
          return null;
        });
  }
}
