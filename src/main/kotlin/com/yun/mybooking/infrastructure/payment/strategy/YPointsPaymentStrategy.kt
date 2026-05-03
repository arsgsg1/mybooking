package com.yun.mybooking.infrastructure.payment.strategy

import com.yun.mybooking.common.exception.BookingException
import com.yun.mybooking.common.exception.ErrorCode
import com.yun.mybooking.domain.payment.PaymentMethod
import com.yun.mybooking.domain.user.UserRepository
import com.yun.mybooking.infrastructure.payment.PaymentRequest
import com.yun.mybooking.infrastructure.payment.PaymentResult
import com.yun.mybooking.infrastructure.payment.PaymentStrategy
import com.yun.mybooking.infrastructure.payment.RefundResult
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class YPointsPaymentStrategy(
    private val userRepository: UserRepository,
) : PaymentStrategy {

    override val supportedMethod = PaymentMethod.Y_POINTS

    override fun process(request: PaymentRequest): PaymentResult {
        val user = userRepository.findById(request.userId)
            .orElseThrow { BookingException(ErrorCode.USER_NOT_FOUND) }

        if (user.yPoints < request.amount) {
            return PaymentResult(
                success = false,
                failureCode = "INSUFFICIENT_POINTS",
                failureReason = "Y포인트 잔액이 부족합니다. 보유: ${user.yPoints}, 요청: ${request.amount}",
            )
        }

        user.yPoints -= request.amount
        userRepository.save(user)

        return PaymentResult(
            success = true,
            transactionId = "points_${UUID.randomUUID()}",
        )
    }

    override fun refund(transactionId: String, amount: Long): RefundResult {
        // 포인트 환불은 orderId로 userId를 추적하여 복원하는 방식이지만,
        // 여기서는 PaymentProcessor가 Payment 엔티티를 통해 userId를 알고 있으므로
        // 실제 프로덕션에서는 별도 이벤트나 보상 트랜잭션으로 처리
        return RefundResult(success = true, refundId = "points_refund_${UUID.randomUUID()}")
    }
}
