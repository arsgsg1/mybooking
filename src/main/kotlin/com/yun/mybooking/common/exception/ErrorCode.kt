package com.yun.mybooking.common.exception

import org.springframework.http.HttpStatus

enum class ErrorCode(val status: HttpStatus, val message: String) {
    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "상품을 찾을 수 없습니다."),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."),
    INVENTORY_NOT_FOUND(HttpStatus.NOT_FOUND, "재고 정보를 찾을 수 없습니다."),

    SOLD_OUT(HttpStatus.CONFLICT, "상품이 매진되었습니다."),
    ALREADY_PURCHASED(HttpStatus.CONFLICT, "이미 구매한 상품입니다."),
    IDEMPOTENCY_PROCESSING(HttpStatus.CONFLICT, "동일한 요청이 처리 중입니다."),

    INVALID_PAYMENT_COMBINATION(HttpStatus.BAD_REQUEST, "사용할 수 없는 결제 수단 조합입니다."),
    INSUFFICIENT_POINTS(HttpStatus.BAD_REQUEST, "Y포인트 잔액이 부족합니다."),
    AMOUNT_MISMATCH(HttpStatus.BAD_REQUEST, "결제 금액이 상품 금액과 일치하지 않습니다."),
    MISSING_IDEMPOTENCY_KEY(HttpStatus.BAD_REQUEST, "X-Idempotency-Key 헤더가 필요합니다."),

    PAYMENT_FAILED(HttpStatus.UNPROCESSABLE_ENTITY, "결제에 실패하였습니다."),
}
