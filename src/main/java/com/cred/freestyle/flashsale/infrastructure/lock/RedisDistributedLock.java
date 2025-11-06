package com.cred.freestyle.flashsale.infrastructure.lock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * Redis-based distributed lock implementation using SET NX EX pattern.
 * Provides mutual exclusion for idempotency checks across multiple application instances.
 *
 * Lock Pattern:
 * - Uses Redis SET command with NX (only set if not exists) and EX (expiry) options
 * - Each lock has a unique token (UUID) to ensure only the lock owner can release it
 * - Automatic expiry prevents deadlocks if holder crashes
 *
 * Usage:
 * String lockKey = "lock:idempotency:" + idempotencyKey;
 * String lockToken = redisLock.acquireLock(lockKey, Duration.ofSeconds(5));
 * if (lockToken != null) {
 *     try {
 *         // Critical section - check and insert idempotency record
 *     } finally {
 *         redisLock.releaseLock(lockKey, lockToken);
 *     }
 * }
 *
 * @author Flash Sale Team
 */
@Service
public class RedisDistributedLock {

    private static final Logger logger = LoggerFactory.getLogger(RedisDistributedLock.class);

    private final StringRedisTemplate stringRedisTemplate;

    public RedisDistributedLock(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * Attempt to acquire a distributed lock.
     * Uses Redis SET NX EX pattern for atomicity.
     *
     * @param lockKey Unique lock key (e.g., "lock:idempotency:user123:SKU-001")
     * @param expiry Lock expiry duration (auto-release if holder crashes)
     * @return Lock token (UUID) if acquired successfully, null if lock already held
     */
    public String acquireLock(String lockKey, Duration expiry) {
        try {
            // Generate unique token for this lock acquisition
            String lockToken = UUID.randomUUID().toString();

            // Try to set the key only if it doesn't exist (NX) with expiry (EX)
            // SET key value NX EX seconds
            Boolean acquired = stringRedisTemplate.opsForValue()
                    .setIfAbsent(lockKey, lockToken, expiry);

            if (Boolean.TRUE.equals(acquired)) {
                logger.debug("Acquired lock: {} with token: {}", lockKey, lockToken);
                return lockToken;
            } else {
                logger.debug("Failed to acquire lock (already held): {}", lockKey);
                return null;
            }
        } catch (Exception e) {
            logger.error("Error acquiring lock for key: {}", lockKey, e);
            return null;
        }
    }

    /**
     * Release a distributed lock.
     * Only releases if the provided token matches the lock owner (prevents accidental release).
     *
     * @param lockKey Lock key
     * @param lockToken Token returned from acquireLock
     * @return true if released successfully, false otherwise
     */
    public boolean releaseLock(String lockKey, String lockToken) {
        try {
            // Only delete if the value matches our token (prevents releasing someone else's lock)
            String currentToken = stringRedisTemplate.opsForValue().get(lockKey);

            if (lockToken != null && lockToken.equals(currentToken)) {
                Boolean deleted = stringRedisTemplate.delete(lockKey);
                if (Boolean.TRUE.equals(deleted)) {
                    logger.debug("Released lock: {} with token: {}", lockKey, lockToken);
                    return true;
                } else {
                    logger.warn("Failed to delete lock key: {} (may have already expired)", lockKey);
                    return false;
                }
            } else {
                logger.warn("Lock token mismatch for key: {} (expected: {}, found: {})",
                        lockKey, lockToken, currentToken);
                return false;
            }
        } catch (Exception e) {
            logger.error("Error releasing lock for key: {}", lockKey, e);
            return false;
        }
    }

    /**
     * Try to acquire a lock with retry logic.
     * Retries for a specified duration with exponential backoff.
     *
     * @param lockKey Lock key
     * @param lockExpiry Lock expiry duration
     * @param retryTimeout Maximum time to retry acquiring the lock
     * @param initialBackoff Initial backoff duration between retries
     * @return Lock token if acquired, null if timeout exceeded
     */
    public String acquireLockWithRetry(
            String lockKey,
            Duration lockExpiry,
            Duration retryTimeout,
            Duration initialBackoff
    ) {
        long startTime = System.currentTimeMillis();
        long timeoutMillis = retryTimeout.toMillis();
        long backoffMillis = initialBackoff.toMillis();
        int attempt = 0;

        while (System.currentTimeMillis() - startTime < timeoutMillis) {
            attempt++;
            String lockToken = acquireLock(lockKey, lockExpiry);

            if (lockToken != null) {
                logger.debug("Acquired lock after {} attempts: {}", attempt, lockKey);
                return lockToken;
            }

            // Exponential backoff with jitter
            long sleepTime = Math.min(backoffMillis * (1L << Math.min(attempt - 1, 10)), 1000);
            sleepTime += (long) (Math.random() * 50); // Add 0-50ms jitter

            try {
                Thread.sleep(sleepTime);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("Lock acquisition interrupted for key: {}", lockKey);
                return null;
            }
        }

        logger.warn("Failed to acquire lock after {}ms and {} attempts: {}",
                retryTimeout.toMillis(), attempt, lockKey);
        return null;
    }

    /**
     * Check if a lock is currently held.
     *
     * @param lockKey Lock key
     * @return true if lock exists (is held), false otherwise
     */
    public boolean isLocked(String lockKey) {
        try {
            return Boolean.TRUE.equals(stringRedisTemplate.hasKey(lockKey));
        } catch (Exception e) {
            logger.error("Error checking lock status for key: {}", lockKey, e);
            return false;
        }
    }
}
