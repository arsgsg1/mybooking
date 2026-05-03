package com.yun.mybooking.domain.payment

import org.springframework.data.jpa.repository.JpaRepository

interface PaymentRepository : JpaRepository<Payment, String> {

    fun findAllByOrderId(orderId: String): List<Payment>
}
