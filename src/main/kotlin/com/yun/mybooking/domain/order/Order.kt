package com.yun.mybooking.domain.order

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(
    name = "orders",
    uniqueConstraints = [
        UniqueConstraint(name = "uq_orders_idempotency_key", columnNames = ["idempotency_key"]),
        UniqueConstraint(name = "uq_orders_user_product", columnNames = ["user_id", "product_id"]),
    ]
)
class Order(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Column(name = "product_id", nullable = false)
    val productId: Long,

    @Column(nullable = false)
    val totalAmount: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: OrderStatus = OrderStatus.PENDING,

    @Column(name = "idempotency_key", nullable = false)
    val idempotencyKey: String,

    @Column(nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    var updatedAt: LocalDateTime = LocalDateTime.now(),
) {
    fun confirm() {
        status = OrderStatus.CONFIRMED
        updatedAt = LocalDateTime.now()
    }

    fun fail() {
        status = OrderStatus.FAILED
        updatedAt = LocalDateTime.now()
    }
}
