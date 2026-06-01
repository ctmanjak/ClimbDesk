package dev.climbdesk.classsession.presentation

import dev.climbdesk.classsession.application.ClassSessionResult
import dev.climbdesk.classsession.domain.ClassSessionStatus
import java.time.Instant

data class ClassSessionResponse(
    val id: Long,
    val title: String,
    val startsAt: Instant,
    val endsAt: Instant,
    val capacity: Int,
    val reservedCount: Int,
    val status: ClassSessionStatus,
    val createdAt: Instant,
    val canceledAt: Instant?,
    val affectedReservationCount: Int,
)

fun ClassSessionResult.toResponse(): ClassSessionResponse =
    ClassSessionResponse(
        id = id,
        title = title,
        startsAt = startsAt,
        endsAt = endsAt,
        capacity = capacity,
        reservedCount = reservedCount,
        status = status,
        createdAt = createdAt,
        canceledAt = canceledAt,
        affectedReservationCount = affectedReservationCount,
    )
