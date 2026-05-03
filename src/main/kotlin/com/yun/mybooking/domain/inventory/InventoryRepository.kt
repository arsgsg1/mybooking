package com.yun.mybooking.domain.inventory

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface InventoryRepository : JpaRepository<Inventory, String>, InventoryRepositoryCustom {

    fun findByProductId(productId: String): Inventory?
}
