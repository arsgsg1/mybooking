package com.yun.mybooking.api

import com.yun.mybooking.common.exception.BookingException
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(BookingException::class)
    fun handleBookingException(ex: BookingException): ResponseEntity<ApiResponse<Nothing>> {
        log.warn("BookingException: code={}, message={}", ex.errorCode, ex.message)
        return ResponseEntity
            .status(ex.errorCode.status)
            .body(ApiResponse.error(ex.errorCode.name, ex.message))
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidationException(ex: MethodArgumentNotValidException): ResponseEntity<ApiResponse<Nothing>> {
        val message = ex.bindingResult.fieldErrors
            .joinToString(", ") { "${it.field}: ${it.defaultMessage}" }
        return ResponseEntity.badRequest().body(ApiResponse.error("VALIDATION_ERROR", message))
    }

    @ExceptionHandler(Exception::class)
    fun handleException(ex: Exception): ResponseEntity<ApiResponse<Nothing>> {
        log.error("Unhandled exception", ex)
        return ResponseEntity.internalServerError()
            .body(ApiResponse.error("INTERNAL_SERVER_ERROR", "서버 내부 오류가 발생했습니다."))
    }
}
