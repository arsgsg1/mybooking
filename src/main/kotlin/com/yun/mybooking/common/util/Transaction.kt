package com.yun.mybooking.common.util

import com.yun.mybooking.common.exception.BookingException
import com.yun.mybooking.common.exception.ErrorCode
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

fun <T> PlatformTransactionManager.transactional(
    readOnly: Boolean = false,
    propagation: Int = TransactionDefinition.PROPAGATION_REQUIRED,
    timeout: Int = TransactionDefinition.TIMEOUT_DEFAULT,
    block: () -> T,
): T {
    val template =
        TransactionTemplate(this).apply {
            this.isReadOnly = readOnly
            this.propagationBehavior = propagation
            this.timeout = timeout
        }
    return template.execute {
        block()
    } ?: throw BookingException(errorCode = ErrorCode.INTERNAL_ERROR, message = ErrorCode.INTERNAL_ERROR.message)
}
