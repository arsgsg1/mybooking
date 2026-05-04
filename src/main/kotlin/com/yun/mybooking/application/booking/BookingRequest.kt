package com.yun.mybooking.application.booking

import com.yun.mybooking.domain.payment.PaymentMethod
import jakarta.validation.Valid
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Positive

data class BookingRequest(
    @field:Positive val productId: Long,
    @field:Positive val userId: Long,
    @field:Positive val totalAmount: Long,
    @field:NotEmpty @field:Valid val payments: List<PaymentItem>,
) {
    data class PaymentItem(
        val method: PaymentMethod,
        @field:Positive val amount: Long,
    )
}
