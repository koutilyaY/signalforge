package com.signalforge.telemetry.ingest;

import com.signalforge.platform.config.SignalForgeProperties;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Per-organization ingestion rate limiting.
 *
 * <p>Implemented as a <b>sliding window counter</b> over two adjacent fixed buckets rather than a
 * plain fixed window. A fixed window lets a client send 2x its quota across a boundary - full quota
 * at 11:59:59 and again at 12:00:00 - which is exactly the burst that knocks over the thing you
 * were trying to protect. Here the previous bucket's count is weighted by how much of it still
 * falls inside the trailing 60 seconds.
 *
 * <p>The whole read-compute-write is a single Lua script so it is atomic on the Redis server. Doing
 * it with separate GET/INCR round trips would be a read-modify-write race across API replicas, and
 * under exactly the concurrent load the limiter exists to handle.
 *
 * <p><b>Degradation:</b> if Redis is unavailable this fails <em>open</em> - ingestion continues
 * unthrottled. That is the right trade-off for a telemetry pipeline whose entire job is to keep
 * working during an incident: dropping a customer's observability data because our cache is down
 * would blind them at the worst possible moment. It is logged at WARN and surfaced as a metric.
 */
@Component
public class IngestionRateLimiter {

  private static final Logger log = LoggerFactory.getLogger(IngestionRateLimiter.class);

  private static final Duration WINDOW = Duration.ofMinutes(1);
  private static final long BUCKET_MS = WINDOW.toMillis();

  /**
   * KEYS[1] current bucket, KEYS[2] previous bucket ARGV[1] limit, ARGV[2] cost, ARGV[3] elapsed ms
   * into current bucket, ARGV[4] ttl seconds
   *
   * <p>Returns {allowed(0|1), estimatedCount}.
   */
  private static final String SLIDING_WINDOW_LUA =
      """
      local current = tonumber(redis.call('GET', KEYS[1]) or '0')
      local previous = tonumber(redis.call('GET', KEYS[2]) or '0')
      local limit = tonumber(ARGV[1])
      local cost = tonumber(ARGV[2])
      local elapsed = tonumber(ARGV[3])
      local ttl = tonumber(ARGV[4])
      local bucketMs = tonumber(ARGV[5])

      -- Weight the previous bucket by the fraction of it still inside the window.
      local weight = 1.0 - (elapsed / bucketMs)
      if weight < 0 then weight = 0 end
      local estimated = (previous * weight) + current

      if (estimated + cost) > limit then
        return {0, math.floor(estimated)}
      end

      local updated = redis.call('INCRBY', KEYS[1], cost)
      -- Set the TTL only when the bucket is new, so the expiry is not pushed
      -- forward on every request.
      if updated == cost then
        redis.call('EXPIRE', KEYS[1], ttl)
      end
      return {1, math.floor(estimated + cost)}
      """;

  private final StringRedisTemplate redis;
  private final RedisScript<List> script;
  private final int defaultLimit;

  public IngestionRateLimiter(StringRedisTemplate redis, SignalForgeProperties properties) {
    this.redis = redis;
    this.script = new DefaultRedisScript<>(SLIDING_WINDOW_LUA, List.class);
    this.defaultLimit = properties.ingestion().defaultRateLimitPerMinute();
  }

  /**
   * @param organizationId tenant to charge
   * @param limitPerMinute the organization's configured ceiling, or null for the default
   * @param cost how many events this request represents - a batch of 200 costs 200, so a client
   *     cannot bypass the limit by batching
   */
  public Decision tryAcquire(java.util.UUID organizationId, Integer limitPerMinute, int cost) {
    int limit = limitPerMinute == null ? defaultLimit : limitPerMinute;
    long now = System.currentTimeMillis();
    long currentBucket = now / BUCKET_MS;
    long elapsed = now % BUCKET_MS;

    // Cache keys are tenant-prefixed. Every key in this system contains the
    // organization id; a shared key would leak quota (and data) across tenants.
    String currentKey = key(organizationId, currentBucket);
    String previousKey = key(organizationId, currentBucket - 1);

    try {
      @SuppressWarnings("unchecked")
      List<Long> result =
          redis.execute(
              script,
              List.of(currentKey, previousKey),
              String.valueOf(limit),
              String.valueOf(cost),
              String.valueOf(elapsed),
              String.valueOf(WINDOW.toSeconds() * 2),
              String.valueOf(BUCKET_MS));

      if (result == null || result.size() < 2) {
        return Decision.allowed(limit, 0);
      }
      boolean allowed = result.get(0) == 1L;
      long observed = result.get(1);
      return allowed ? Decision.allowed(limit, observed) : Decision.denied(limit, observed);

    } catch (DataAccessException | IllegalStateException e) {
      log.warn(
          "Rate limiter unavailable for org={}, allowing request (failing open): {}",
          organizationId,
          e.toString());
      return Decision.degraded(limit);
    }
  }

  private static String key(java.util.UUID organizationId, long bucket) {
    return "sf:ratelimit:ingest:" + organizationId + ':' + bucket;
  }

  /**
   * @param allowed whether the caller may proceed
   * @param limit the ceiling in effect, for the X-RateLimit-Limit header
   * @param observed estimated usage in the trailing window
   * @param degraded true when the limiter could not consult Redis and defaulted to allow
   */
  public record Decision(boolean allowed, int limit, long observed, boolean degraded) {

    static Decision allowed(int limit, long observed) {
      return new Decision(true, limit, observed, false);
    }

    static Decision denied(int limit, long observed) {
      return new Decision(false, limit, observed, false);
    }

    static Decision degraded(int limit) {
      return new Decision(true, limit, 0, true);
    }

    public long remaining() {
      return Math.max(0, limit - observed);
    }
  }
}
