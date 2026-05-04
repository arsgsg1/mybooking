package com.yun.mybooking.domain.payment

import org.springframework.data.jpa.repository.JpaRepository

interface PaymentRepository : JpaRepository<Payment, Long> {

    fun findAllByOrderId(orderId: Long): List<Payment>
}
