package com.yun.mybooking.common.exception

class BookingException(
    val errorCode: ErrorCode,
    override val message: String = errorCode.message,
) : RuntimeException(message)
