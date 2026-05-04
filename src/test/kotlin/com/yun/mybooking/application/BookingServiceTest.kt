package com.yun.mybooking.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.yun.mybooking.application.booking.BookingRequest
import com.yun.mybooking.application.booking.BookingService
import com.yun.mybooking.common.exception.BookingException
import com.yun.mybooking.common.exception.ErrorCode
import com.yun.mybooking.domain.inventory.InventoryRepository
import com.yun.mybooking.domain.order.Order
import com.yun.mybooking.domain.order.OrderRepository
import com.yun.mybooking.domain.order.OrderStatus
import com.yun.mybooking.domain.payment.PaymentMethod
import com.yun.mybooking.domain.payment.PaymentRepository
import com.yun.mybooking.domain.product.Product
import com.yun.mybooking.domain.product.ProductRepository
import com.yun.mybooking.domain.product.ProductStatus
import com.yun.mybooking.infrastructure.idempotency.IdempotencyRecord
import com.yun.mybooking.infrastructure.idempotency.IdempotencyService
import com.yun.mybooking.infrastructure.idempotency.IdempotencyStatus
import com.yun.mybooking.infrastructure.inventory.InventoryRedisService
import com.yun.mybooking.infrastructure.inventory.InventoryRedisService.DecrementResult
import com.yun.mybooking.infrastructure.payment.CompletedPayment
import com.yun.mybooking.infrastructure.payment.PaymentProcessor
import com.yun.mybooking.infrastructure.payment.PaymentValidator
import com.yun.mybooking.infrastructure.payment.ProcessResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Optional

class BookingServiceTest {

    private val productRepository = mockk<ProductRepository>()
    private val inventoryRepository = mockk<InventoryRepository>()
    private val orderRepository = mockk<OrderRepository>()
    private val paymentRepository = mockk<PaymentRepository>()
    private val inventoryRedisService = mockk<InventoryRedisService>()
    private val idempotencyService = mockk<IdempotencyService>()
    private val paymentProcessor = mockk<PaymentProcessor>()
    private val paymentValidator = mockk<PaymentValidator>(relaxed = true)
    private val objectMapper = ObjectMapper()
        .registerModule(JavaTimeModule())
        .registerModule(kotlinModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    private lateinit var bookingService: BookingService

    private val product = Product(
        id = 1L,
        name = "테스트 스위트",
        price = 150000L,
        checkInDate = LocalDate.of(2026, 5, 10),
        checkOutDate = LocalDate.of(2026, 5, 11),
        checkInTime = LocalTime.of(15, 0),
        checkOutTime = LocalTime.of(11, 0),
        saleOpenTime = LocalDateTime.of(2026, 5, 1, 0, 0),
        status = ProductStatus.ACTIVE,
    )

    private val savedOrder = Order(
        id = 1L,
        userId = 1L,
        productId = 1L,
        totalAmount = 150000L,
        guestName = "홍길동",
        idempotencyKey = "idem-key-001",
        status = OrderStatus.CONFIRMED,
    )

    @BeforeEach
    fun setUp() {
        bookingService = BookingService(
            productRepository,
            inventoryRepository,
            orderRepository,
            paymentRepository,
            inventoryRedisService,
            idempotencyService,
            paymentProcessor,
            paymentValidator,
            objectMapper,
        )
    }

    @Test
    fun `정상 예약 흐름 - 재고 선점 후 결제 성공 시 CONFIRMED 반환`() {
        val idempotencyKey = "idem-key-001"
        val request = bookingRequest()

        every { idempotencyService.getRecord(idempotencyKey) } returns null
        every { idempotencyService.tryAcquire(idempotencyKey) } returns true
        every { productRepository.findById(1L) } returns Optional.of(product)
        every { orderRepository.existsByUserIdAndProductId(1L, 1L) } returns false
        every { inventoryRedisService.decrement(1L) } returns DecrementResult.SUCCESS
        every { orderRepository.save(any()) } returns savedOrder
        every { paymentProcessor.processAll(any()) } returns ProcessResult.Success(
            listOf(CompletedPayment(PaymentMethod.CREDIT_CARD, 150000L, "cc_tx_001"))
        )
        every { paymentRepository.save(any()) } returnsArgument 0
        every { idempotencyService.complete(any(), any()) } returns Unit

        val response = bookingService.book(idempotencyKey, request)

        assert(response.bookingId == 1L)
        assert(response.status == OrderStatus.CONFIRMED)
    }

    @Test
    fun `재고 소진 시 SOLD_OUT 예외 발생`() {
        val idempotencyKey = "idem-key-002"

        every { idempotencyService.getRecord(idempotencyKey) } returns null
        every { idempotencyService.tryAcquire(idempotencyKey) } returns true
        every { productRepository.findById(1L) } returns Optional.of(product)
        every { orderRepository.existsByUserIdAndProductId(1L, 1L) } returns false
        every { inventoryRedisService.decrement(1L) } returns DecrementResult.INSUFFICIENT_STOCK
        every { idempotencyService.fail(any(), any(), any()) } returns Unit

        val ex = assertThrows<BookingException> {
            bookingService.book(idempotencyKey, bookingRequest())
        }
        assert(ex.errorCode == ErrorCode.SOLD_OUT)
    }

    @Test
    fun `중복 요청 - COMPLETED 상태면 캐시된 응답 반환하고 DB 미접근`() {
        val idempotencyKey = "idem-key-003"
        val completedRecord = IdempotencyRecord(
            status = IdempotencyStatus.COMPLETED,
            result = objectMapper.writeValueAsString(
                mapOf(
                    "bookingId" to 1,
                    "status" to "CONFIRMED",
                    "productName" to "테스트 스위트",
                    "checkInDate" to "2026-05-10",
                    "checkOutDate" to "2026-05-11",
                    "checkInTime" to "15:00:00",
                    "checkOutTime" to "11:00:00",
                    "totalAmount" to 150000,
                    "payments" to emptyList<Any>(),
                    "createdAt" to "2026-05-01T00:00:01",
                )
            )
        )

        every { idempotencyService.getRecord(idempotencyKey) } returns completedRecord

        bookingService.book(idempotencyKey, bookingRequest())

        verify(exactly = 0) { productRepository.findById(any<Long>()) }
    }

    @Test
    fun `동일 요청 처리 중 - PROCESSING 상태면 409 예외 발생`() {
        val idempotencyKey = "idem-key-004"

        every { idempotencyService.getRecord(idempotencyKey) } returns IdempotencyRecord(
            status = IdempotencyStatus.PROCESSING
        )

        val ex = assertThrows<BookingException> {
            bookingService.book(idempotencyKey, bookingRequest())
        }
        assert(ex.errorCode == ErrorCode.IDEMPOTENCY_PROCESSING)
    }

    @Test
    fun `결제 실패 시 재고 롤백`() {
        val idempotencyKey = "idem-key-005"

        every { idempotencyService.getRecord(idempotencyKey) } returns null
        every { idempotencyService.tryAcquire(idempotencyKey) } returns true
        every { productRepository.findById(1L) } returns Optional.of(product)
        every { orderRepository.existsByUserIdAndProductId(1L, 1L) } returns false
        every { inventoryRedisService.decrement(1L) } returns DecrementResult.SUCCESS
        every { inventoryRedisService.increment(1L) } returns Unit
        every { orderRepository.save(any()) } returns savedOrder
        every { paymentProcessor.processAll(any()) } returns ProcessResult.Failure(
            failedMethod = PaymentMethod.CREDIT_CARD,
            failureReason = "한도 초과",
        )
        every { idempotencyService.fail(any(), any(), any()) } returns Unit

        assertThrows<BookingException> {
            bookingService.book(idempotencyKey, bookingRequest())
        }

        verify(exactly = 1) { inventoryRedisService.increment(1L) }
    }

    @Test
    fun `이미 구매한 사용자 - ALREADY_PURCHASED 예외 발생`() {
        val idempotencyKey = "idem-key-006"

        every { idempotencyService.getRecord(idempotencyKey) } returns null
        every { idempotencyService.tryAcquire(idempotencyKey) } returns true
        every { productRepository.findById(1L) } returns Optional.of(product)
        every { orderRepository.existsByUserIdAndProductId(1L, 1L) } returns true
        every { idempotencyService.fail(any(), any(), any()) } returns Unit

        val ex = assertThrows<BookingException> {
            bookingService.book(idempotencyKey, bookingRequest())
        }
        assert(ex.errorCode == ErrorCode.ALREADY_PURCHASED)
    }

    private fun bookingRequest() = BookingRequest(
        productId = 1L,
        userId = 1L,
        guestName = "홍길동",
        guestPhone = "010-1234-5678",
        totalAmount = 150000L,
        payments = listOf(
            BookingRequest.PaymentItem(
                method = PaymentMethod.CREDIT_CARD,
                amount = 150000L,
                cardToken = "card_tok_test",
            )
        ),
    )
}
