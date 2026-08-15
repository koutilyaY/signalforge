package com.signalforge.platform.tenant;

import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Binds a tenant for a unit of work, covering both the case where a connection has yet to be
 * borrowed and the case where one is already in play.
 *
 * <p>{@link TenantAwareDataSource} stamps {@code signalforge.current_org} when a connection is
 * taken from the pool, which is the right hook for the common path: a request arrives, the filter
 * binds the principal, and the first repository call borrows an already-stamped connection.
 *
 * <p>It is <em>not</em> sufficient when the tenant changes inside an active transaction. Tenant
 * bootstrap does exactly that - {@code registerOrganization} is transactional so the organization
 * and its first admin are created atomically, but the tenant id is only known once inside. By then
 * the connection has already been borrowed and stamped with nothing.
 *
 * <p>So this also issues {@code set_config(..., is_local => true)} against the live transaction,
 * which PostgreSQL reverts automatically at commit or rollback. Belt and braces, and neither alone
 * is enough.
 */
@Component
public class TenantBinder {

  private final JdbcTemplate jdbcTemplate;

  public TenantBinder(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * Runs {@code action} with {@code organizationId} bound both in-process and on the connection.
   */
  public <T> T callAs(UUID organizationId, Supplier<T> action) {
    return TenantContext.callAs(
        TenantPrincipal.system(organizationId),
        () -> {
          bindToActiveTransaction(organizationId);
          return action.get();
        });
  }

  public void runAs(UUID organizationId, Runnable action) {
    callAs(
        organizationId,
        () -> {
          action.run();
          return null;
        });
  }

  /**
   * Applies the setting to the transaction currently in progress, if any.
   *
   * <p>{@code is_local => true} scopes it to the transaction, so it cannot leak onto a pooled
   * connection after commit - the same hazard {@link TenantAwareDataSource} guards against on
   * release.
   */
  private void bindToActiveTransaction(UUID organizationId) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      return;
    }
    jdbcTemplate.queryForObject(
        "SELECT set_config('signalforge.current_org', ?, true)",
        String.class,
        organizationId.toString());
  }
}
