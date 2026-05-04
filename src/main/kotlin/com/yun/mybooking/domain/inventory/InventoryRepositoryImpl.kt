package com.yun.mybooking.domain.inventory

import com.querydsl.jpa.impl.JPAQueryFactory
import com.yun.mybooking.domain.inventory.QInventory.inventory

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

    override fun atomicRelease(productId: Long, amount: Int): Long =
        queryFactory
            .update(inventory)
            .set(inventory.reservedStock, inventory.reservedStock.subtract(amount))
            .where(
                inventory.productId.eq(productId),
                inventory.reservedStock.goe(amount),
            )
            .execute()
}
