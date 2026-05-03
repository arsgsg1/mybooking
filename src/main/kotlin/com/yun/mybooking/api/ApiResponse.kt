package com.yun.mybooking.api

data class ApiResponse<T>(
    val success: Boolean,
    val data: T? = null,
    val error: ErrorResponse? = null,
) {
    companion object {
        fun <T> ok(data: T) = ApiResponse(success = true, data = data)
        fun error(code: String, message: String) = ApiResponse<Nothing>(
            success = false,
            error = ErrorResponse(code, message),
        )
    }

    data class ErrorResponse(val code: String, val message: String)
}
