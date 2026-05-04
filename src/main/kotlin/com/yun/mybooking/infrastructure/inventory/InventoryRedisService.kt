package com.yun.mybooking.infrastructure.inventory

import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Service

@Service
class InventoryRedisService(
    private val redisTemplate: StringRedisTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val KEY_PREFIX = "inventory:"

        private val DECREMENT_SCRIPT = DefaultRedisScript<Long>().apply {
            setScriptText(
                """
                local current = tonumber(redis.call('GET', KEYS[1]))
                if current == nil then return -1 end
                if current < tonumber(ARGV[1]) then return -2 end
                return redis.call('DECRBY', KEYS[1], ARGV[1])
                """.trimIndent()
            )
            resultType = Long::class.java
        }
    }

    fun initInventory(productId: Long, stock: Int) {
        redisTemplate.opsForValue().set(key(productId), stock.toString())
        log.info("Redis 재고 초기화: productId={}, stock={}", productId, stock)
    }

    fun decrement(productId: Long, amount: Int = 1): DecrementResult {
        val result = redisTemplate.execute(
            DECREMENT_SCRIPT,
            listOf(key(productId)),
            amount.toString(),
        )
        return when (result) {
            -1L -> DecrementResult.KEY_NOT_FOUND
            -2L -> DecrementResult.INSUFFICIENT_STOCK
            else -> DecrementResult.SUCCESS
        }
    }

    fun increment(productId: Long, amount: Int = 1) {
        redisTemplate.opsForValue().increment(key(productId), amount.toLong())
    }

    fun getStock(productId: Long): Int? =
        redisTemplate.opsForValue().get(key(productId))?.toIntOrNull()

    private fun key(productId: Long) = "$KEY_PREFIX$productId"
}
