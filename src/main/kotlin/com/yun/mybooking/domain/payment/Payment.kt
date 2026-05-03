package com.yun.mybooking.domain.payment

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "payments")
class Payment(
    @Id
    val id: String,

    @Column(name = "order_id", nullable = false)
    val orderId: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val method: PaymentMethod,

    @Column(nullable = false)
    val amount: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: PaymentStatus = PaymentStatus.PENDING,

    var pgTransactionId: String? = null,
    var failureReason: String? = null,

    @Column(nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
) {
    fun complete(pgTransactionId: String) {
        this.status = PaymentStatus.COMPLETED
        this.pgTransactionId = pgTransactionId
    }

    fun fail(reason: String) {
        this.status = PaymentStatus.FAILED
        this.failureReason = reason
    }

    fun refund() {
        this.status = PaymentStatus.REFUNDED
    }
}
