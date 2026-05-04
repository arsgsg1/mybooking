package com.yun.mybooking.api

import com.yun.mybooking.application.checkout.CheckoutService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/checkout")
class CheckoutController(
    private val checkoutService: CheckoutService,
) {

    @GetMapping("/{productId}")
    fun getCheckout(
        @PathVariable productId: Long,
        @RequestParam userId: Long,
    ): ApiResponse<*> = ApiResponse.ok(checkoutService.getCheckout(productId, userId))
}
