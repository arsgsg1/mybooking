package com.yun.mybooking.domain.user

import jakarta.persistence.*

@Entity
@Table(name = "users")
class User(
    @Id
    val id: String,

    @Column(nullable = false, unique = true)
    val email: String,

    @Column(nullable = false)
    val name: String,

    val phone: String? = null,

    @Column(nullable = false)
    var yPoints: Long = 0,
)
