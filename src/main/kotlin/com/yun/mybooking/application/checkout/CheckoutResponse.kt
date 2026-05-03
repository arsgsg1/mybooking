package com.yun.mybooking.application.checkout

import com.yun.mybooking.domain.payment.PaymentMethod
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

data class CheckoutResponse(
    val product: ProductInfo,
    val user: UserInfo,
    val availablePaymentMethods: List<PaymentMethod>,
) {
    data class ProductInfo(
        val id: String,
        val name: String,
        val description: String?,
        val price: Long,
        val checkInDate: LocalDate,
        val checkOutDate: LocalDate,
        val checkInTime: LocalTime,
        val checkOutTime: LocalTime,
        val saleOpenTime: LocalDateTime,
        val remainingStock: Int,
        val location: String?,
    )

    data class UserInfo(
        val id: String,
        val name: String,
        val availablePoints: Long,
    )
}
