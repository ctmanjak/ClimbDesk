package dev.climbdesk.classsession.domain

import java.time.Instant

data class ClassSessionCanceledEvent(
    val classSessionId: Long,
    val cancelReason: String,
    val affectedReservationCount: Int,
    val occurredAt: Instant,
)
