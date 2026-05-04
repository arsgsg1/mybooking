package com.yun.mybooking.application

import com.yun.mybooking.application.booking.BookingRequest
import com.yun.mybooking.application.booking.BookingService
import com.yun.mybooking.common.exception.BookingException
import com.yun.mybooking.common.exception.ErrorCode
import com.yun.mybooking.domain.inventory.Inventory
import com.yun.mybooking.domain.inventory.InventoryRepository
import com.yun.mybooking.domain.order.Order
import com.yun.mybooking.domain.order.OrderRepository
import com.yun.mybooking.domain.order.OrderStatus
import com.yun.mybooking.domain.payment.PaymentMethod
import com.yun.mybooking.domain.payment.PaymentRepository
import com.yun.mybooking.domain.product.Product
import com.yun.mybooking.domain.product.ProductRepository
import com.yun.mybooking.domain.product.ProductStatus
import com.yun.mybooking.domain.user.User
import com.yun.mybooking.domain.user.UserRepository
import com.yun.mybooking.infrastructure.idempotency.IdempotencyService
import com.yun.mybooking.infrastructure.idempotency.IdempotencyState
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
    private val userRepository = mockk<UserRepository>()
    private val inventoryRepository = mockk<InventoryRepository>()
    private val orderRepository = mockk<OrderRepository>()
    private val paymentRepository = mockk<PaymentRepository>()
    private val inventoryRedisService = mockk<InventoryRedisService>()
    private val idempotencyService = mockk<IdempotencyService>()
    private val paymentProcessor = mockk<PaymentProcessor>()
    private val paymentValidator = mockk<PaymentValidator>(relaxed = true)

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

    private val user = User(
        id = 1L,
        email = "alice@example.com",
        name = "김앨리스",
        phone = "010-1111-2222",
        yPoints = 100000L,
    )

    private val inventory = Inventory(
        id = 1L,
        productId = 1L,
        totalStock = 10,
        reservedStock = 0,
    )

    private val savedOrder = Order(
        id = 1L,
        userId = 1L,
        productId = 1L,
        totalAmount = 150000L,
        status = OrderStatus.CONFIRMED,
    )

    @BeforeEach
    fun setUp() {
        bookingService = BookingService(
            productRepository,
            userRepository,
            inventoryRepository,
            orderRepository,
            paymentRepository,
            inventoryRedisService,
            idempotencyService,
            paymentProcessor,
            paymentValidator,
        )
    }

    @Test
    fun `정상 예약 흐름 - 재고 선점 후 결제 성공 시 CONFIRMED 반환`() {
        val idempotencyKey = "idem-key-001"

        every { idempotencyService.getState(idempotencyKey) } returns null
        every { idempotencyService.tryAcquire(idempotencyKey) } returns true
        every { productRepository.findById(1L) } returns Optional.of(product)
        every { userRepository.findById(1L) } returns Optional.of(user)
        every { orderRepository.existsByUserIdAndProductId(1L, 1L) } returns false
        every { inventoryRedisService.decrement(1L) } returns DecrementResult.SUCCESS
        every { orderRepository.save(any()) } returns savedOrder
        every { paymentProcessor.processAll(any()) } returns ProcessResult.Success(
            listOf(CompletedPayment(PaymentMethod.CREDIT_CARD, 150000L, "cc_tx_001"))
        )
        every { inventoryRepository.findByProductIdWithLock(1L) } returns inventory
        every { paymentRepository.save(any()) } returnsArgument 0
        every { idempotencyService.complete(idempotencyKey, 1L) } returns Unit

        val response = bookingService.book(idempotencyKey, bookingRequest())

        assert(response.bookingId == 1L)
        assert(response.status == OrderStatus.CONFIRMED)
    }

    @Test
    fun `재고 소진 시 SOLD_OUT 예외 발생`() {
        val idempotencyKey = "idem-key-002"

        every { idempotencyService.getState(idempotencyKey) } returns null
        every { idempotencyService.tryAcquire(idempotencyKey) } returns true
        every { productRepository.findById(1L) } returns Optional.of(product)
        every { userRepository.findById(1L) } returns Optional.of(user)
        every { orderRepository.existsByUserIdAndProductId(1L, 1L) } returns false
        every { inventoryRedisService.decrement(1L) } returns DecrementResult.INSUFFICIENT_STOCK
        every { idempotencyService.release(idempotencyKey) } returns Unit

        val ex = assertThrows<BookingException> {
            bookingService.book(idempotencyKey, bookingRequest())
        }
        assert(ex.errorCode == ErrorCode.SOLD_OUT)
    }

    @Test
    fun `중복 요청 - COMPLETED 상태면 DB에서 응답 재구성 후 반환`() {
        val idempotencyKey = "idem-key-003"

        every { idempotencyService.getState(idempotencyKey) } returns IdempotencyState.Completed(1L)
        every { orderRepository.findById(1L) } returns Optional.of(savedOrder)
        every { productRepository.findById(1L) } returns Optional.of(product)
        every { paymentRepository.findAllByOrderId(1L) } returns emptyList()

        val response = bookingService.book(idempotencyKey, bookingRequest())

        assert(response.bookingId == 1L)
        verify(exactly = 0) { inventoryRedisService.decrement(any()) }
    }

    @Test
    fun `동일 요청 처리 중 - PROCESSING 상태면 PENDING 응답 반환`() {
        val idempotencyKey = "idem-key-004"

        every { idempotencyService.getState(idempotencyKey) } returns IdempotencyState.Processing

        val response = bookingService.book(idempotencyKey, bookingRequest())

        assert(response.status == OrderStatus.PENDING)
        assert(response.bookingId == null)
    }

    @Test
    fun `결제 실패 시 재고 롤백 및 멱등키 해제`() {
        val idempotencyKey = "idem-key-005"

        every { idempotencyService.getState(idempotencyKey) } returns null
        every { idempotencyService.tryAcquire(idempotencyKey) } returns true
        every { productRepository.findById(1L) } returns Optional.of(product)
        every { userRepository.findById(1L) } returns Optional.of(user)
        every { orderRepository.existsByUserIdAndProductId(1L, 1L) } returns false
        every { inventoryRedisService.decrement(1L) } returns DecrementResult.SUCCESS
        every { inventoryRedisService.increment(1L) } returns Unit
        every { orderRepository.save(any()) } returns savedOrder
        every { paymentProcessor.processAll(any()) } returns ProcessResult.Failure(
            failedMethod = PaymentMethod.CREDIT_CARD,
            failureReason = "한도 초과",
        )
        every { idempotencyService.release(idempotencyKey) } returns Unit

        assertThrows<BookingException> {
            bookingService.book(idempotencyKey, bookingRequest())
        }

        verify(exactly = 1) { inventoryRedisService.increment(1L) }
        verify(exactly = 1) { idempotencyService.release(idempotencyKey) }
    }

    @Test
    fun `이미 구매한 사용자 - ALREADY_PURCHASED 예외 발생`() {
        val idempotencyKey = "idem-key-006"

        every { idempotencyService.getState(idempotencyKey) } returns null
        every { idempotencyService.tryAcquire(idempotencyKey) } returns true
        every { productRepository.findById(1L) } returns Optional.of(product)
        every { userRepository.findById(1L) } returns Optional.of(user)
        every { orderRepository.existsByUserIdAndProductId(1L, 1L) } returns true
        every { idempotencyService.release(idempotencyKey) } returns Unit

        val ex = assertThrows<BookingException> {
            bookingService.book(idempotencyKey, bookingRequest())
        }
        assert(ex.errorCode == ErrorCode.ALREADY_PURCHASED)
    }

    @Test
    fun `결제 실패 후 동일 멱등키로 재시도 시 새 요청으로 처리`() {
        val idempotencyKey = "idem-key-007"

        every { idempotencyService.getState(idempotencyKey) } returns null
        every { idempotencyService.tryAcquire(idempotencyKey) } returns true
        every { productRepository.findById(1L) } returns Optional.of(product)
        every { userRepository.findById(1L) } returns Optional.of(user)
        every { orderRepository.existsByUserIdAndProductId(1L, 1L) } returns false
        every { inventoryRedisService.decrement(1L) } returns DecrementResult.SUCCESS
        every { inventoryRedisService.increment(1L) } returns Unit
        every { orderRepository.save(any()) } returns savedOrder
        every { paymentProcessor.processAll(any()) } returns ProcessResult.Failure(
            failedMethod = PaymentMethod.CREDIT_CARD,
            failureReason = "한도 초과",
        )
        every { idempotencyService.release(idempotencyKey) } returns Unit

        assertThrows<BookingException> { bookingService.book(idempotencyKey, bookingRequest()) }

        // 2차 재시도: release 후 키 없음 → 신규 처리
        every { paymentProcessor.processAll(any()) } returns ProcessResult.Success(
            listOf(CompletedPayment(PaymentMethod.CREDIT_CARD, 150000L, "cc_tx_002"))
        )
        every { inventoryRepository.findByProductIdWithLock(1L) } returns inventory
        every { paymentRepository.save(any()) } returnsArgument 0
        every { idempotencyService.complete(idempotencyKey, 1L) } returns Unit

        val response = bookingService.book(idempotencyKey, bookingRequest())
        assert(response.status == OrderStatus.CONFIRMED)
    }

    private fun bookingRequest() = BookingRequest(
        productId = 1L,
        userId = 1L,
        totalAmount = 150000L,
        payments = listOf(
            BookingRequest.PaymentItem(
                method = PaymentMethod.CREDIT_CARD,
                amount = 150000L,
            )
        ),
    )
}
