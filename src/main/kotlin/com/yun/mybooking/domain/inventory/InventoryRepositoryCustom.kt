package com.yun.mybooking.domain.inventory

interface InventoryRepositoryCustom {
    fun atomicReserve(productId: String, amount: Int): Long
    fun atomicRelease(productId: String, amount: Int): Long
}
