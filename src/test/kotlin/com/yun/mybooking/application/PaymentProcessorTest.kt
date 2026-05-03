package com.yun.mybooking.application

import com.yun.mybooking.common.exception.BookingException
import com.yun.mybooking.common.exception.ErrorCode
import com.yun.mybooking.domain.payment.PaymentMethod
import com.yun.mybooking.infrastructure.payment.PaymentRequest
import com.yun.mybooking.infrastructure.payment.PaymentResult
import com.yun.mybooking.infrastructure.payment.PaymentStrategy
import com.yun.mybooking.infrastructure.payment.PaymentValidator
import com.yun.mybooking.infrastructure.payment.ProcessResult
import com.yun.mybooking.infrastructure.payment.RefundResult
import com.yun.mybooking.infrastructure.payment.PaymentProcessor
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PaymentProcessorTest {

    private fun makeStrategy(method: PaymentMethod, result: PaymentResult): PaymentStrategy =
        mockk<PaymentStrategy>().also {
            every { it.supportedMethod } returns method
            every { it.process(any()) } returns result
            every { it.refund(any(), any()) } returns RefundResult(success = true, refundId = "refund-001")
        }

    @Test
    fun `단일 결제 성공`() {
        val strategy = makeStrategy(
            PaymentMethod.CREDIT_CARD,
            PaymentResult(success = true, transactionId = "cc_tx_001"),
        )
        val processor = PaymentProcessor(listOf(strategy))

        val result = processor.processAll(
            listOf(PaymentRequest("order-001", "user-001", 150000L, PaymentMethod.CREDIT_CARD, cardToken = "tok"))
        )

        assert(result is ProcessResult.Success)
        assert((result as ProcessResult.Success).payments.size == 1)
    }

    @Test
    fun `복합 결제 - CREDIT_CARD 성공 후 Y_POINTS 실패 시 CREDIT_CARD 환불`() {
        val ccStrategy = makeStrategy(
            PaymentMethod.CREDIT_CARD,
            PaymentResult(success = true, transactionId = "cc_tx_001"),
        )
        val pointsStrategy = makeStrategy(
            PaymentMethod.Y_POINTS,
            PaymentResult(success = false, failureReason = "포인트 잔액 부족"),
        )
        val processor = PaymentProcessor(listOf(ccStrategy, pointsStrategy))

        val result = processor.processAll(
            listOf(
                PaymentRequest("order-001", "user-001", 100000L, PaymentMethod.CREDIT_CARD, cardToken = "tok"),
                PaymentRequest("order-001", "user-001", 50000L, PaymentMethod.Y_POINTS),
            )
        )

        assert(result is ProcessResult.Failure)
        verify(exactly = 1) { ccStrategy.refund("cc_tx_001", 100000L) }
    }

    @Test
    fun `PaymentValidator - CREDIT_CARD + Y_PAY 조합 시 예외 발생`() {
        val validator = PaymentValidator()

        val ex = assertThrows<BookingException> {
            validator.validate(
                listOf(
                    PaymentRequest("order-001", "user-001", 100000L, PaymentMethod.CREDIT_CARD, cardToken = "tok"),
                    PaymentRequest("order-001", "user-001", 50000L, PaymentMethod.Y_PAY, yPayToken = "ypay"),
                ),
                expectedTotal = 150000L,
            )
        }
        assert(ex.errorCode == ErrorCode.INVALID_PAYMENT_COMBINATION)
    }

    @Test
    fun `PaymentValidator - 금액 불일치 시 예외 발생`() {
        val validator = PaymentValidator()

        val ex = assertThrows<BookingException> {
            validator.validate(
                listOf(PaymentRequest("order-001", "user-001", 100000L, PaymentMethod.CREDIT_CARD, cardToken = "tok")),
                expectedTotal = 150000L,
            )
        }
        assert(ex.errorCode == ErrorCode.AMOUNT_MISMATCH)
    }
}
