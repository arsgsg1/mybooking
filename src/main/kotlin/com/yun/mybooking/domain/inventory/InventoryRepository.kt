package com.yun.mybooking.domain.inventory

import com.querydsl.jpa.impl.JPAQueryFactory
import com.yun.mybooking.domain.inventory.QInventory.inventory
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.stereotype.Repository

@Repository
interface InventoryRepository : JpaRepository<Inventory, Long>, InventoryRepositoryCustom {

    fun findByProductId(productId: Long): Inventory?
}

interface InventoryRepositoryCustom {
    @Modifying(clearAutomatically = true)
    fun atomicReserve(productId: Long, amount: Int): Long
}

class InventoryRepositoryImpl(
    private val queryFactory: JPAQueryFactory,
) : InventoryRepositoryCustom {

    override fun atomicReserve(productId: Long, amount: Int): Long =
        queryFactory
            .update(inventory)
            .set(inventory.reservedStock, inventory.reservedStock.add(amount))
            .where(
                inventory.productId.eq(productId),
                inventory.totalStock.subtract(inventory.reservedStock).goe(amount),
            )
            .execute()
}
