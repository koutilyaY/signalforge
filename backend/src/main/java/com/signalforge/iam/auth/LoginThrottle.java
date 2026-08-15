package com.signalforge.iam.auth;

import com.signalforge.platform.config.SignalForgeProperties;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Counts failed logins per email and locks the account out for a window.
 *
 * <p>Backed by Redis so the counter is shared across API replicas - a per-instance counter would
 * let an attacker get N attempts per replica.
 *
 * <p><b>Degradation:</b> if Redis is down this <em>fails open</em> (login still works, throttling
 * is skipped) rather than closed. That is a deliberate trade-off and worth defending: failing
 * closed would turn a Redis outage into a total authentication outage, which is a far worse and
 * much more likely incident than the brute-force attempt this guards against. Passwords are still
 * bcrypt, and the failure is logged loudly. See docs/runbooks/redis-unavailable.md.
 */
@Component
public class LoginThrottle {

  private static final Logger log = LoggerFactory.getLogger(LoginThrottle.class);
  private static final String KEY_PREFIX = "sf:login-attempts:";

  private final StringRedisTemplate redis;
  private final int maxAttempts;
  private final Duration window;

  public LoginThrottle(StringRedisTemplate redis, SignalForgeProperties properties) {
    this.redis = redis;
    this.maxAttempts = properties.security().loginMaxAttempts();
    this.window = properties.security().loginLockoutWindow();
  }

  public boolean isLockedOut(String email) {
    try {
      String value = redis.opsForValue().get(key(email));
      return value != null && Integer.parseInt(value) >= maxAttempts;
    } catch (DataAccessException | NumberFormatException e) {
      log.warn("Login throttle unavailable, allowing attempt (failing open): {}", e.toString());
      return false;
    }
  }

  /** Increments the counter, setting the TTL only on the first failure so the window is fixed. */
  public void recordFailure(String email) {
    try {
      String redisKey = key(email);
      Long attempts = redis.opsForValue().increment(redisKey);
      if (attempts != null && attempts == 1L) {
        redis.expire(redisKey, window);
      }
    } catch (DataAccessException e) {
      log.warn("Could not record failed login attempt: {}", e.toString());
    }
  }

  public void reset(String email) {
    try {
      redis.delete(key(email));
    } catch (DataAccessException e) {
      log.warn("Could not reset login attempts: {}", e.toString());
    }
  }

  private static String key(String email) {
    return KEY_PREFIX + email;
  }
}
