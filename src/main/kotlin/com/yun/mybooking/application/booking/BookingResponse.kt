package com.yun.mybooking.application.booking

import com.yun.mybooking.domain.order.OrderStatus
import com.yun.mybooking.domain.payment.PaymentMethod
import com.yun.mybooking.domain.payment.PaymentStatus
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

data class BookingResponse(
    val bookingId: Long?,
    val status: OrderStatus,
    val productName: String?,
    val checkInDate: LocalDate?,
    val checkOutDate: LocalDate?,
    val checkInTime: LocalTime?,
    val checkOutTime: LocalTime?,
    val totalAmount: Long?,
    val payments: List<PaymentInfo>,
    val createdAt: LocalDateTime?,
) {
    data class PaymentInfo(
        val method: PaymentMethod,
        val amount: Long,
        val status: PaymentStatus,
        val transactionId: String?,
    )

    companion object {
        fun pending() = BookingResponse(
            bookingId = null,
            status = OrderStatus.PENDING,
            productName = null,
            checkInDate = null,
            checkOutDate = null,
            checkInTime = null,
            checkOutTime = null,
            totalAmount = null,
            payments = emptyList(),
            createdAt = null,
        )
    }
}
