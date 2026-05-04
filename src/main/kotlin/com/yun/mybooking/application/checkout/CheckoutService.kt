package com.yun.mybooking.application.checkout

import com.yun.mybooking.common.exception.BookingException
import com.yun.mybooking.common.exception.ErrorCode
import com.yun.mybooking.domain.inventory.InventoryRepository
import com.yun.mybooking.domain.payment.PaymentMethod
import com.yun.mybooking.domain.product.ProductRepository
import com.yun.mybooking.domain.user.UserRepository
import com.yun.mybooking.infrastructure.inventory.InventoryRedisService
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service

@Service
class CheckoutService(
    private val productRepository: ProductRepository,
    private val userRepository: UserRepository,
    private val inventoryRepository: InventoryRepository,
    private val inventoryRedisService: InventoryRedisService,
) {

    fun getCheckout(productId: Long, userId: Long): CheckoutResponse {
        val product = productRepository.findByIdOrNull(productId)
            ?: throw BookingException(ErrorCode.PRODUCT_NOT_FOUND)

        val user = userRepository.findByIdOrNull(userId)
            ?: throw BookingException(ErrorCode.USER_NOT_FOUND)

        val remainingStock = inventoryRedisService.getStock(productId)
            ?: inventoryRepository.findByProductId(productId)
                ?.let { it.totalStock - it.reservedStock }
            ?: throw BookingException(ErrorCode.INVENTORY_NOT_FOUND)

        return CheckoutResponse(
            product = CheckoutResponse.ProductInfo(
                id = product.id,
                name = product.name,
                description = product.description,
                price = product.price,
                checkInDate = product.checkInDate,
                checkOutDate = product.checkOutDate,
                checkInTime = product.checkInTime,
                checkOutTime = product.checkOutTime,
                saleOpenTime = product.saleOpenTime,
                remainingStock = remainingStock,
                location = product.location,
            ),
            user = CheckoutResponse.UserInfo(
                id = user.id,
                name = user.name,
                availablePoints = user.yPoints,
            ),
            availablePaymentMethods = PaymentMethod.entries,
        )
    }
}
