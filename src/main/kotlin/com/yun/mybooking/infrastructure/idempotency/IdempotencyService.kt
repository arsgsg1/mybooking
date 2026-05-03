package com.yun.mybooking.infrastructure.idempotency

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.time.Duration

enum class IdempotencyStatus { PROCESSING, COMPLETED, FAILED }

data class IdempotencyRecord(
    val status: IdempotencyStatus,
    val result: String? = null,
    val errorCode: String? = null,
    val message: String? = null,
)

@Service
class IdempotencyService(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val KEY_PREFIX = "idempotency:"
        private val TTL = Duration.ofHours(24)
    }

    fun tryAcquire(key: String): Boolean {
        val acquired = redisTemplate.opsForValue()
            .setIfAbsent(redisKey(key), IdempotencyStatus.PROCESSING.name, TTL)
        return acquired == true
    }

    fun getRecord(key: String): IdempotencyRecord? {
        val value = redisTemplate.opsForValue().get(redisKey(key)) ?: return null
        return try {
            objectMapper.readValue(value, IdempotencyRecord::class.java)
        } catch (e: Exception) {
            // PROCESSING 상태는 단순 문자열로 저장되므로 별도 처리
            if (value == IdempotencyStatus.PROCESSING.name) {
                IdempotencyRecord(status = IdempotencyStatus.PROCESSING)
            } else {
                log.warn("멱등성 레코드 역직렬화 실패: key={}", key, e)
                null
            }
        }
    }

    fun complete(key: String, result: Any) {
        val record = IdempotencyRecord(
            status = IdempotencyStatus.COMPLETED,
            result = objectMapper.writeValueAsString(result),
        )
        redisTemplate.opsForValue().set(redisKey(key), objectMapper.writeValueAsString(record), TTL)
    }

    fun fail(key: String, errorCode: String, message: String) {
        val record = IdempotencyRecord(
            status = IdempotencyStatus.FAILED,
            errorCode = errorCode,
            message = message,
        )
        redisTemplate.opsForValue().set(redisKey(key), objectMapper.writeValueAsString(record), TTL)
    }

    fun release(key: String) {
        redisTemplate.delete(redisKey(key))
    }

    private fun redisKey(key: String) = "$KEY_PREFIX$key"
}
