package com.signalforge.platform.tenant;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Stamps the current tenant onto every database connection so PostgreSQL's row-level security
 * policies (V4) can enforce isolation.
 *
 * <p><b>Why a DataSource wrapper rather than an aspect.</b> The policies need {@code
 * signalforge.current_org} set on the actual connection running the statement. An {@code @Around}
 * advice on {@code @Transactional} would have to reach through Spring's {@code DataSourceUtils} to
 * find that connection, and would silently miss anything that opens a connection outside a declared
 * transaction. Wrapping {@code getConnection()} catches every path by construction, because there
 * is no other way to obtain a connection.
 *
 * <p><b>Why the setting is cleared on close.</b> Connections are pooled and handed to unrelated
 * requests. A leaked {@code current_org} would mean the next borrower of that connection sees the
 * previous tenant's rows - the exact failure this class exists to prevent, arrived at from the
 * other direction. The returned connection is therefore proxied so {@code close()} resets the
 * setting before the connection goes back to the pool.
 *
 * <p>When no tenant is bound - startup, Flyway, the scheduler between tenants - the setting is
 * cleared rather than left stale, and the policies then match nothing.
 */
public class TenantAwareDataSource extends DelegatingDataSource {

  private static final Logger log = LoggerFactory.getLogger(TenantAwareDataSource.class);

  static final String SETTING = "signalforge.current_org";

  public TenantAwareDataSource(DataSource target) {
    super(target);
  }

  @Override
  public Connection getConnection() throws SQLException {
    return prepare(super.getConnection());
  }

  @Override
  public Connection getConnection(String username, String password) throws SQLException {
    return prepare(super.getConnection(username, password));
  }

  private Connection prepare(Connection connection) throws SQLException {
    applyTenant(
        connection, TenantContext.current().map(TenantPrincipal::organizationId).orElse(null));
    return proxy(connection);
  }

  private static void applyTenant(Connection connection, UUID organizationId) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      if (organizationId == null) {
        statement.execute("SELECT set_config('" + SETTING + "', '', false)");
      } else {
        // set_config with a bound-safe literal: the value is a UUID we produced,
        // never client input, but it is still funnelled through set_config
        // rather than string-concatenated into a SET statement.
        statement.execute("SELECT set_config('" + SETTING + "', '" + organizationId + "', false)");
      }
    }
  }

  /** Proxies {@code close()} so the tenant setting never outlives the borrow. */
  private static Connection proxy(Connection target) {
    return (Connection)
        Proxy.newProxyInstance(
            Connection.class.getClassLoader(),
            new Class<?>[] {Connection.class},
            new ResettingConnectionHandler(target));
  }

  private record ResettingConnectionHandler(Connection target) implements InvocationHandler {

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
      switch (method.getName()) {
        case "close" -> {
          reset();
          target.close();
          return null;
        }
        case "equals" -> {
          return proxy == args[0];
        }
        case "hashCode" -> {
          return System.identityHashCode(proxy);
        }
        case "unwrap" -> {
          Class<?> type = (Class<?>) args[0];
          if (type.isInstance(proxy)) {
            return proxy;
          }
          return target.unwrap(type);
        }
        case "isWrapperFor" -> {
          Class<?> type = (Class<?>) args[0];
          return type.isInstance(proxy) || target.isWrapperFor(type);
        }
        default -> {
          try {
            return method.invoke(target, args);
          } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getTargetException();
          }
        }
      }
    }

    private void reset() {
      // Best effort. A connection that is already broken cannot leak a setting,
      // and throwing here would mask the original failure that closed it.
      try {
        if (!target.isClosed()) {
          try (Statement statement = target.createStatement()) {
            statement.execute("SELECT set_config('" + SETTING + "', '', false)");
          }
        }
      } catch (SQLException e) {
        log.debug("Could not clear tenant setting on connection release: {}", e.toString());
      }
    }
  }
}
