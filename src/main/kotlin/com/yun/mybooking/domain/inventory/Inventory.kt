package com.yun.mybooking.domain.inventory

import com.yun.mybooking.common.exception.BookingException
import com.yun.mybooking.common.exception.ErrorCode
import jakarta.persistence.*

@Entity
@Table(name = "inventories")
class Inventory(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false, unique = true)
    val productId: Long,

    @Column(nullable = false)
    val totalStock: Int,

    @Column(nullable = false)
    var reservedStock: Int = 0,
) {
    val remainingStock: Int get() = totalStock - reservedStock

    fun reserve() {
        if (reservedStock >= totalStock) {
            throw BookingException(ErrorCode.SOLD_OUT)
        } else {
            reservedStock += 1
        }
    }
}
