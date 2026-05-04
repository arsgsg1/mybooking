package com.yun.mybooking.domain.order

import org.springframework.data.jpa.repository.JpaRepository

interface OrderRepository : JpaRepository<Order, Long> {

    fun existsByUserIdAndProductIdAndStatus(userId: Long, productId: Long, status: OrderStatus): Boolean
}
