package com.signalforge.platform.config;

import com.signalforge.platform.tenant.TenantAwareDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wraps whatever {@link DataSource} Spring Boot auto-configured so every connection carries the
 * current tenant for PostgreSQL row-level security.
 *
 * <p>A {@link BeanPostProcessor} rather than replacing the DataSource bean outright: Boot's
 * auto-configuration, Testcontainers' {@code @ServiceConnection} and any future connection-pool
 * change all still apply, and this decorates the result instead of competing with it.
 *
 * <p>The method is {@code static} because a BeanPostProcessor must be instantiated before the beans
 * it processes; a non-static factory method would force the enclosing configuration class to be
 * created too early and Spring logs a warning about it.
 *
 * <p>Flyway shares this DataSource and therefore runs with no tenant bound. That is correct: DDL is
 * not subject to RLS, and the only seed data in the migrations goes into {@code roles}, which is
 * deliberately not a protected table.
 */
@Configuration
public class TenantDataSourceConfig {

  @Bean
  static BeanPostProcessor tenantAwareDataSourceWrapper() {
    return new BeanPostProcessor() {
      @Override
      public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof DataSource dataSource && !(bean instanceof TenantAwareDataSource)) {
          return new TenantAwareDataSource(dataSource);
        }
        return bean;
      }
    };
  }
}
