package com.yun.mybooking.domain.inventory

import jakarta.persistence.*

@Entity
@Table(name = "inventories")
class Inventory(
    @Id
    val id: String,

    @Column(nullable = false, unique = true)
    val productId: String,

    @Column(nullable = false)
    val totalStock: Int,

    @Column(nullable = false)
    var reservedStock: Int = 0,
)
