package com.yun.mybooking.api

import com.yun.mybooking.application.booking.BookingRequest
import com.yun.mybooking.application.booking.BookingService
import com.yun.mybooking.common.exception.BookingException
import com.yun.mybooking.common.exception.ErrorCode
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/bookings")
class BookingController(
    private val bookingService: BookingService,
) {

    @PostMapping
    fun book(
        @RequestHeader("X-Idempotency-Key") idempotencyKey: String?,
        @RequestBody @Valid request: BookingRequest,
    ): ApiResponse<*> {
        if (idempotencyKey.isNullOrBlank()) {
            throw BookingException(ErrorCode.MISSING_IDEMPOTENCY_KEY)
        }
        return ApiResponse.ok(bookingService.book(idempotencyKey, request))
    }
}
