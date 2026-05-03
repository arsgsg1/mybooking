package com.yun.mybooking.application.booking

import com.fasterxml.jackson.databind.ObjectMapper
import com.yun.mybooking.common.exception.BookingException
import com.yun.mybooking.common.exception.ErrorCode
import com.yun.mybooking.domain.inventory.InventoryRepository
import com.yun.mybooking.domain.order.Order
import com.yun.mybooking.domain.order.OrderRepository
import com.yun.mybooking.domain.payment.Payment
import com.yun.mybooking.domain.payment.PaymentRepository
import com.yun.mybooking.domain.product.Product
import com.yun.mybooking.domain.product.ProductRepository
import com.yun.mybooking.infrastructure.idempotency.IdempotencyService
import com.yun.mybooking.infrastructure.idempotency.IdempotencyStatus
import com.yun.mybooking.infrastructure.inventory.InventoryRedisService
import com.yun.mybooking.infrastructure.inventory.InventoryRedisService.DecrementResult
import com.yun.mybooking.infrastructure.payment.CompletedPayment
import com.yun.mybooking.infrastructure.payment.PaymentProcessor
import com.yun.mybooking.infrastructure.payment.PaymentRequest
import com.yun.mybooking.infrastructure.payment.PaymentValidator
import com.yun.mybooking.infrastructure.payment.ProcessResult
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class BookingService(
    private val productRepository: ProductRepository,
    private val inventoryRepository: InventoryRepository,
    private val orderRepository: OrderRepository,
    private val paymentRepository: PaymentRepository,
    private val inventoryRedisService: InventoryRedisService,
    private val idempotencyService: IdempotencyService,
    private val paymentProcessor: PaymentProcessor,
    private val paymentValidator: PaymentValidator,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun book(idempotencyKey: String, request: BookingRequest): BookingResponse {
        val existing = idempotencyService.getRecord(idempotencyKey)
        if (existing != null) {
            return when (existing.status) {
                IdempotencyStatus.PROCESSING -> throw BookingException(ErrorCode.IDEMPOTENCY_PROCESSING)
                IdempotencyStatus.COMPLETED -> objectMapper.readValue(existing.result!!, BookingResponse::class.java)
                IdempotencyStatus.FAILED -> throw BookingException(
                    errorCode = ErrorCode.valueOf(existing.errorCode ?: ErrorCode.PAYMENT_FAILED.name),
                    message = existing.message ?: ErrorCode.PAYMENT_FAILED.message,
                )
            }
        }

        if (!idempotencyService.tryAcquire(idempotencyKey)) {
            throw BookingException(ErrorCode.IDEMPOTENCY_PROCESSING)
        }

        return runCatching {
            executeBooking(idempotencyKey, request)
        }.onSuccess { response ->
            idempotencyService.complete(idempotencyKey, response)
        }.onFailure { ex ->
            val errorCode = if (ex is BookingException) ex.errorCode else ErrorCode.PAYMENT_FAILED
            idempotencyService.fail(idempotencyKey, errorCode.name, ex.message ?: errorCode.message)
        }.getOrThrow()
    }

    private fun executeBooking(idempotencyKey: String, request: BookingRequest): BookingResponse {
        val product = productRepository.findById(request.productId)
            .orElseThrow { BookingException(ErrorCode.PRODUCT_NOT_FOUND) }

        if (orderRepository.existsByUserIdAndProductId(request.userId, request.productId)) {
            throw BookingException(ErrorCode.ALREADY_PURCHASED)
        }

        val paymentRequests = request.payments.map { item ->
            PaymentRequest(
                orderId = "",
                userId = request.userId,
                amount = item.amount,
                method = item.method,
                cardToken = item.cardToken,
                yPayToken = item.yPayToken,
            )
        }
        paymentValidator.validate(paymentRequests, product.price)

        reserveInventory(request.productId)

        return runCatching {
            processOrderAndPayment(idempotencyKey, request, product, paymentRequests)
        }.onFailure {
            inventoryRedisService.increment(request.productId)
        }.getOrThrow()
    }

    @CircuitBreaker(name = "redis-inventory", fallbackMethod = "reserveInventoryFallback")
    fun reserveInventory(productId: String) {
        when (inventoryRedisService.decrement(productId)) {
            DecrementResult.INSUFFICIENT_STOCK,
            DecrementResult.KEY_NOT_FOUND -> throw BookingException(ErrorCode.SOLD_OUT)
            DecrementResult.SUCCESS -> Unit
        }
    }

    @Suppress("unused")
    fun reserveInventoryFallback(productId: String, ex: Exception) {
        log.warn("Redis 서킷브레이커 오픈 — DB fallback으로 재고 선점: productId={}", productId)
        val affected = inventoryRepository.atomicReserve(productId, 1)
        if (affected == 0L) throw BookingException(ErrorCode.SOLD_OUT)
    }

    @Transactional
    fun processOrderAndPayment(
        idempotencyKey: String,
        request: BookingRequest,
        product: Product,
        paymentRequests: List<PaymentRequest>,
    ): BookingResponse {
        val orderId = UUID.randomUUID().toString()

        val order = orderRepository.save(
            Order(
                id = orderId,
                userId = request.userId,
                productId = request.productId,
                totalAmount = product.price,
                guestName = request.guestName,
                guestPhone = request.guestPhone,
                idempotencyKey = idempotencyKey,
            )
        )

        val requestsWithOrderId = paymentRequests.map { it.copy(orderId = orderId) }

        when (val result = paymentProcessor.processAll(requestsWithOrderId)) {
            is ProcessResult.Failure -> {
                order.fail()
                orderRepository.save(order)
                throw BookingException(ErrorCode.PAYMENT_FAILED, result.failureReason)
            }
            is ProcessResult.Success -> {
                order.confirm()
                orderRepository.save(order)
                val payments = savePayments(orderId, result.payments)
                return toResponse(order, product, payments)
            }
        }
    }

    private fun savePayments(orderId: String, completed: List<CompletedPayment>): List<Payment> =
        completed.map { cp ->
            paymentRepository.save(
                Payment(
                    id = UUID.randomUUID().toString(),
                    orderId = orderId,
                    method = cp.method,
                    amount = cp.amount,
                ).also { it.complete(cp.transactionId) }
            )
        }

    private fun toResponse(order: Order, product: Product, payments: List<Payment>) =
        BookingResponse(
            bookingId = order.id,
            status = order.status,
            productName = product.name,
            checkInDate = product.checkInDate,
            checkOutDate = product.checkOutDate,
            checkInTime = product.checkInTime,
            checkOutTime = product.checkOutTime,
            totalAmount = order.totalAmount,
            payments = payments.map { p ->
                BookingResponse.PaymentInfo(
                    method = p.method,
                    amount = p.amount,
                    status = p.status,
                    transactionId = p.pgTransactionId,
                )
            },
            createdAt = order.createdAt,
        )
}
