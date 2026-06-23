package dev.climbdesk.common.error

enum class ErrorCode(
    val defaultMessage: String,
) {
    VALIDATION_FAILED("Request validation failed."),
    UNAUTHORIZED("Authentication is required."),
    INVALID_CREDENTIALS("Invalid credentials."),
    FORBIDDEN("Access is denied."),
    RESOURCE_NOT_FOUND("Resource not found."),
    CONCURRENCY_CONFLICT("Request could not be completed due to a concurrency conflict."),
    INTERNAL_SERVER_ERROR("Internal server error."),

    ADMIN_USER_INACTIVE("Admin user is inactive."),
    ADMIN_USER_NOT_FOUND("Admin user not found."),
    DUPLICATE_ADMIN_USER_EMAIL("Admin user email already exists."),
    LAST_ACTIVE_MANAGER_REQUIRED("At least one active manager is required."),
    ADMIN_USER_ROLE_CHANGE_NOT_ALLOWED("Admin user role change is not allowed."),
    ADMIN_USER_STATUS_CHANGE_NOT_ALLOWED("Admin user status change is not allowed."),

    MEMBER_NOT_FOUND("Member not found."),
    DUPLICATE_MEMBER_PHONE("Member phone already exists."),
    MEMBER_INACTIVE("Member is inactive."),

    PASS_PRODUCT_NOT_FOUND("Pass product not found."),
    MEMBER_PASS_NOT_FOUND("Member pass not found."),
    MEMBER_PASS_NOT_AVAILABLE("Member pass is not available."),
    MEMBER_PASS_RESTORE_NOT_ALLOWED("Member pass restore is not allowed."),
    MEMBER_PASS_VERSION_CONFLICT("Member pass version conflict."),

    CLASS_SESSION_NOT_FOUND("Class session not found."),
    CLASS_SESSION_NOT_OPEN("Class session is not open."),
    CLASS_SESSION_FULL("Class session is full."),
    CLASS_SESSION_ALREADY_CANCELED("Class session is already canceled."),

    RESERVATION_NOT_FOUND("Reservation not found."),
    DUPLICATE_RESERVATION("Member already has a confirmed reservation for this class session."),
    RESERVATION_ALREADY_CANCELED("Reservation is already canceled."),
}
