package com.yun.mybooking.infrastructure.payment

import com.yun.mybooking.common.exception.BookingException
import com.yun.mybooking.common.exception.ErrorCode
import com.yun.mybooking.domain.payment.PaymentMethod
import org.springframework.stereotype.Component

@Component
class PaymentValidator {

    fun validate(payments: List<PaymentRequest>, expectedTotal: Long) {
        require(payments.isNotEmpty()) { "결제 수단이 없습니다." }

        val methods = payments.map { it.method }

        if (methods.contains(PaymentMethod.CREDIT_CARD) && methods.contains(PaymentMethod.Y_PAY)) {
            throw BookingException(ErrorCode.INVALID_PAYMENT_COMBINATION)
        }

        val primaryMethods = methods.filter { it != PaymentMethod.Y_POINTS }
        if (primaryMethods.size > 1) {
            throw BookingException(ErrorCode.INVALID_PAYMENT_COMBINATION)
        }

        val actualTotal = payments.sumOf { it.amount }
        if (actualTotal != expectedTotal) {
            throw BookingException(ErrorCode.AMOUNT_MISMATCH)
        }
    }
}
