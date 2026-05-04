package com.yun.mybooking.infrastructure.inventory

import com.yun.mybooking.domain.inventory.InventoryRepository
import com.yun.mybooking.domain.product.ProductStatus
import com.yun.mybooking.domain.product.ProductRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

/**
 * 개발 산출물 검증 편의를 위해 레디스에 재고 데이터를 미리 채워놓기 위한 용도
 */
@Component
class InventoryInitializer(
    private val productRepository: ProductRepository,
    private val inventoryRepository: InventoryRepository,
    private val inventoryRedisService: InventoryRedisService,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        val activeProducts = productRepository.findAll()
            .filter { it.status == ProductStatus.ACTIVE }

        activeProducts.forEach { product ->
            val inventory = inventoryRepository.findByProductId(product.id) ?: return@forEach
            val available = inventory.totalStock - inventory.reservedStock
            inventoryRedisService.initInventory(product.id, available)
        }

        log.info("Redis 재고 초기화 완료: {}개 상품", activeProducts.size)
    }
}
