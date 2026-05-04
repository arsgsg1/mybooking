package com.yun.mybooking.infrastructure.payment.gateway

data class PgPaymentRequest(
    val orderId: Long,
    val amount: Long,
    val token: String,
)

data class PgPaymentResponse(
    val success: Boolean,
    val transactionId: String? = null,
    val failureCode: String? = null,
    val failureReason: String? = null,
)

data class PgRefundRequest(
    val transactionId: String,
    val amount: Long,
)

data class PgRefundResponse(
    val success: Boolean,
    val refundId: String? = null,
    val failureReason: String? = null,
)

interface PgGateway {
    fun pay(request: PgPaymentRequest): PgPaymentResponse
    fun refund(request: PgRefundRequest): PgRefundResponse
}
