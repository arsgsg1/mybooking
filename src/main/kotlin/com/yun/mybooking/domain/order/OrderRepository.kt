package com.yun.mybooking.domain.order

import org.springframework.data.jpa.repository.JpaRepository

interface OrderRepository : JpaRepository<Order, String> {

    fun existsByUserIdAndProductId(userId: String, productId: String): Boolean
}
