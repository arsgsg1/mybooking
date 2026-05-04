package com.yun.mybooking.infrastructure.payment

import com.yun.mybooking.domain.payment.PaymentMethod

interface PaymentStrategy {
    val supportedMethod: PaymentMethod
    fun process(request: PaymentRequest): PaymentResult
    fun refund(transactionId: String, amount: Long): RefundResult
}

data class PaymentRequest(
    val orderId: Long,
    val userId: Long,
    val amount: Long,
    val method: PaymentMethod,
    val cardToken: String? = null,
    val yPayToken: String? = null,
)

data class PaymentResult(
    val success: Boolean,
    val transactionId: String? = null,
    val failureCode: String? = null,
    val failureReason: String? = null,
)

data class RefundResult(
    val success: Boolean,
    val refundId: String? = null,
    val failureReason: String? = null,
)
