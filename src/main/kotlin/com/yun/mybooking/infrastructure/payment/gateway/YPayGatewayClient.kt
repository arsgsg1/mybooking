package com.yun.mybooking.infrastructure.payment.gateway

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class YPayGatewayClient : PgGateway {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun pay(request: PgPaymentRequest): PgPaymentResponse {
        log.info("[YPay Stub] 결제 요청: orderId={}, amount={}", request.orderId, request.amount)
        return PgPaymentResponse(
            success = true,
            transactionId = "ypay_${UUID.randomUUID()}",
        )
    }

    override fun refund(request: PgRefundRequest): PgRefundResponse {
        log.info("[YPay Stub] 환불 요청: transactionId={}", request.transactionId)
        return PgRefundResponse(
            success = true,
            refundId = "ypay_refund_${UUID.randomUUID()}",
        )
    }
}
