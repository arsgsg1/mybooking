package com.yun.mybooking.infrastructure.payment.strategy

import com.yun.mybooking.domain.payment.PaymentMethod
import com.yun.mybooking.infrastructure.payment.PaymentRequest
import com.yun.mybooking.infrastructure.payment.PaymentResult
import com.yun.mybooking.infrastructure.payment.PaymentStrategy
import com.yun.mybooking.infrastructure.payment.RefundResult
import com.yun.mybooking.infrastructure.payment.gateway.PgPaymentRequest
import com.yun.mybooking.infrastructure.payment.gateway.PgRefundRequest
import com.yun.mybooking.infrastructure.payment.gateway.YPayGatewayClient
import org.springframework.stereotype.Component

@Component
class YPayPaymentStrategy(
    private val gateway: YPayGatewayClient,
) : PaymentStrategy {

    override val supportedMethod = PaymentMethod.Y_PAY

    override fun process(request: PaymentRequest): PaymentResult {
        val response = gateway.pay(
            PgPaymentRequest(
                orderId = request.orderId,
                amount = request.amount,
                token = requireNotNull(request.yPayToken) { "yPayToken is required for Y_PAY" },
            )
        )
        return PaymentResult(
            success = response.success,
            transactionId = response.transactionId,
            failureCode = response.failureCode,
            failureReason = response.failureReason,
        )
    }

    override fun refund(transactionId: String, amount: Long): RefundResult {
        val response = gateway.refund(PgRefundRequest(transactionId, amount))
        return RefundResult(
            success = response.success,
            refundId = response.refundId,
            failureReason = response.failureReason,
        )
    }
}
