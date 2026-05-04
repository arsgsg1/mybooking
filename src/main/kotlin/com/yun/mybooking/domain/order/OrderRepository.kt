package com.yun.mybooking.domain.order

import org.springframework.data.jpa.repository.JpaRepository

interface OrderRepository : JpaRepository<Order, Long> {

    fun existsByUserIdAndProductId(userId: Long, productId: Long): Boolean
}
