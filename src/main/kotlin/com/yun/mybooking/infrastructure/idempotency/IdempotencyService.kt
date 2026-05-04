package com.yun.mybooking.infrastructure.idempotency

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.time.Duration

sealed class IdempotencyState {
    object Processing : IdempotencyState()
}

@Service
class IdempotencyService(
    private val redisTemplate: StringRedisTemplate
) {
    companion object {
        private const val KEY_PREFIX = "idempotency:"
        private const val PROCESSING = "PROCESSING"
        private val TTL = Duration.ofMinutes(5)
    }

    fun tryAcquire(key: String): Boolean =
        redisTemplate.opsForValue().setIfAbsent(redisKey(key), PROCESSING, TTL) == true

    fun getState(key: String): IdempotencyState? {
        redisTemplate.opsForValue().get(redisKey(key)) ?: return null
        return IdempotencyState.Processing
    }

    fun complete(key: String) {
        redisTemplate.delete(redisKey(key))
    }

    fun release(key: String) {
        redisTemplate.delete(redisKey(key))
    }

    private fun redisKey(key: String) = "$KEY_PREFIX$key"
}
