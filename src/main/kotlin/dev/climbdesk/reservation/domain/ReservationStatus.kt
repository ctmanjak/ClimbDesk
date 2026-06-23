package dev.climbdesk.reservation.domain

enum class ReservationStatus {
    CONFIRMED,
    CANCELED,
}

enum class ReservationCancelReason {
    USER_REQUESTED,
    CLASS_SESSION_CANCELED,
}
