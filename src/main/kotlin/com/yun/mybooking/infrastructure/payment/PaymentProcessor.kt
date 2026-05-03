package com.yun.mybooking.infrastructure.payment

import com.yun.mybooking.domain.payment.PaymentMethod
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class PaymentProcessor(strategies: List<PaymentStrategy>) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val strategyMap: Map<PaymentMethod, PaymentStrategy> =
        strategies.associateBy { it.supportedMethod }

    // Y_POINTS는 DB 차감이므로 롤백이 단순한 외부 결제(PG)를 먼저 처리
    private val processingOrder = listOf(
        PaymentMethod.CREDIT_CARD,
        PaymentMethod.Y_PAY,
        PaymentMethod.Y_POINTS,
    )

    fun processAll(requests: List<PaymentRequest>): ProcessResult {
        val sorted = requests.sortedBy { processingOrder.indexOf(it.method) }
        val completed = mutableListOf<Pair<PaymentRequest, PaymentResult>>()

        for (request in sorted) {
            val strategy = requireNotNull(strategyMap[request.method]) {
                "지원하지 않는 결제 수단: ${request.method}"
            }
            val result = strategy.process(request)

            if (!result.success) {
                log.warn("결제 실패: method={}, reason={}", request.method, result.failureReason)
                rollbackAll(completed)
                return ProcessResult.Failure(
                    failedMethod = request.method,
                    failureReason = result.failureReason ?: "알 수 없는 오류",
                )
            }
            completed.add(request to result)
        }

        return ProcessResult.Success(completed.map { (req, res) ->
            CompletedPayment(req.method, req.amount, res.transactionId!!)
        })
    }

    fun refundAll(completedPayments: List<CompletedPayment>) {
        completedPayments.reversed().forEach { payment ->
            runCatching {
                strategyMap[payment.method]?.refund(payment.transactionId, payment.amount)
            }.onFailure {
                log.error("환불 실패: method={}, transactionId={}", payment.method, payment.transactionId, it)
            }
        }
    }

    private fun rollbackAll(completed: List<Pair<PaymentRequest, PaymentResult>>) {
        completed.reversed().forEach { (req, res) ->
            if (res.transactionId != null) {
                runCatching {
                    strategyMap[req.method]?.refund(res.transactionId, req.amount)
                }.onFailure {
                    log.error("롤백 실패: method={}, transactionId={}", req.method, res.transactionId, it)
                }
            }
        }
    }
}

data class CompletedPayment(
    val method: PaymentMethod,
    val amount: Long,
    val transactionId: String,
)

sealed class ProcessResult {
    data class Success(val payments: List<CompletedPayment>) : ProcessResult()
    data class Failure(val failedMethod: PaymentMethod, val failureReason: String) : ProcessResult()
}
