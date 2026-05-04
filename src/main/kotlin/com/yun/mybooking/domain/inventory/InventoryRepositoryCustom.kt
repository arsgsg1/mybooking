package com.yun.mybooking.domain.inventory

interface InventoryRepositoryCustom {
    fun atomicReserve(productId: Long, amount: Int): Long
    fun atomicRelease(productId: Long, amount: Int): Long
}
