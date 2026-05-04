package com.yun.mybooking.infrastructure.idempotency

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.time.Duration

sealed class IdempotencyState {
    object Processing : IdempotencyState()
    data class Completed(val orderId: Long) : IdempotencyState()
}

@Service
class IdempotencyService(
    private val redisTemplate: StringRedisTemplate
) {
    companion object {
        private const val KEY_PREFIX = "idempotency:"
        private const val PROCESSING = "PROCESSING"
        private val TTL = Duration.ofHours(24)
    }

    fun tryAcquire(key: String): Boolean =
        redisTemplate.opsForValue().setIfAbsent(redisKey(key), PROCESSING, TTL) == true

    fun getState(key: String): IdempotencyState? {
        val value = redisTemplate.opsForValue().get(redisKey(key)) ?: return null
        return when (value) {
            PROCESSING -> IdempotencyState.Processing
            else -> IdempotencyState.Completed(value.toLong())
        }
    }

    fun complete(key: String, orderId: Long) {
        redisTemplate.opsForValue().set(redisKey(key), orderId.toString(), TTL)
    }

    fun release(key: String) {
        redisTemplate.delete(redisKey(key))
    }

    private fun redisKey(key: String) = "$KEY_PREFIX$key"
}
