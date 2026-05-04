package com.yun.mybooking.api

import com.yun.mybooking.application.booking.BookingRequest
import com.yun.mybooking.application.booking.BookingResponse
import com.yun.mybooking.application.booking.BookingService
import com.yun.mybooking.common.exception.BookingException
import com.yun.mybooking.common.exception.ErrorCode
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/bookings")
class BookingController(
    private val bookingService: BookingService,
) {

    @PostMapping
    fun book(
        @RequestHeader("X-Idempotency-Key") idempotencyKey: String?,
        @RequestBody @Valid request: BookingRequest,
    ): ApiResponse<BookingResponse> {
        if (idempotencyKey.isNullOrBlank()) {
            throw BookingException(ErrorCode.MISSING_IDEMPOTENCY_KEY)
        }
        return ApiResponse.ok(bookingService.book(idempotencyKey, request))
    }
}
