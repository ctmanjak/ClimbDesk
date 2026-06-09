package dev.climbdesk.common.error

import org.springframework.http.HttpStatus

object ErrorCodeStatusMapper {
    fun statusOf(errorCode: ErrorCode): HttpStatus =
        when (errorCode) {
            ErrorCode.VALIDATION_FAILED -> HttpStatus.BAD_REQUEST
            ErrorCode.UNAUTHORIZED,
            ErrorCode.INVALID_CREDENTIALS,
            -> HttpStatus.UNAUTHORIZED
            ErrorCode.FORBIDDEN,
            ErrorCode.ADMIN_USER_INACTIVE,
            -> HttpStatus.FORBIDDEN
            ErrorCode.MEMBER_INACTIVE,
            ErrorCode.MEMBER_PASS_VERSION_CONFLICT,
            ErrorCode.CLASS_SESSION_NOT_OPEN,
            ErrorCode.CLASS_SESSION_FULL,
            ErrorCode.CLASS_SESSION_ALREADY_CANCELED,
            ErrorCode.DUPLICATE_RESERVATION,
            ErrorCode.RESERVATION_ALREADY_CANCELED,
            -> HttpStatus.CONFLICT
            ErrorCode.RESOURCE_NOT_FOUND,
            ErrorCode.ADMIN_USER_NOT_FOUND,
            ErrorCode.MEMBER_NOT_FOUND,
            ErrorCode.PASS_PRODUCT_NOT_FOUND,
            ErrorCode.MEMBER_PASS_NOT_FOUND,
            ErrorCode.CLASS_SESSION_NOT_FOUND,
            ErrorCode.RESERVATION_NOT_FOUND,
            -> HttpStatus.NOT_FOUND
            ErrorCode.INTERNAL_SERVER_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR
            else -> HttpStatus.CONFLICT
        }
}
