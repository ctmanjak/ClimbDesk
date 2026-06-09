package dev.climbdesk.classsession.domain

import dev.climbdesk.common.error.DomainException
import dev.climbdesk.common.error.ErrorCode
import java.time.Instant

data class ClassSession(
    val id: Long,
    val title: String,
    val startsAt: Instant,
    val endsAt: Instant,
    val capacity: Int,
    val reservedCount: Int,
    val status: ClassSessionStatus,
    val createdAt: Instant? = null,
    val canceledAt: Instant? = null,
    val cancelReason: String? = null,
    val affectedReservationCount: Int = 0,
) {
    fun reserveSeat(): ClassSession {
        if (status != ClassSessionStatus.OPEN) {
            throw DomainException(ErrorCode.CLASS_SESSION_NOT_OPEN)
        }
        if (reservedCount >= capacity) {
            throw DomainException(ErrorCode.CLASS_SESSION_FULL)
        }

        return copy(reservedCount = reservedCount + 1)
    }

    fun cancelSeat(): ClassSession {
        if (reservedCount <= 0) {
            throw DomainException(ErrorCode.VALIDATION_FAILED, "reservedCount must be greater than 0.")
        }

        return copy(reservedCount = reservedCount - 1)
    }

    fun cancel(
        reason: String,
        canceledAt: Instant = Instant.now(),
        affectedReservationCount: Int = reservedCount,
    ): ClassSession {
        if (status == ClassSessionStatus.CANCELED) {
            throw DomainException(ErrorCode.CLASS_SESSION_ALREADY_CANCELED)
        }
        if (reason.isBlank()) {
            throw DomainException(ErrorCode.VALIDATION_FAILED, "reason must not be blank.")
        }
        if (reason.length > 500) {
            throw DomainException(ErrorCode.VALIDATION_FAILED, "reason must be less than or equal to 500 characters.")
        }

        return copy(
            reservedCount = 0,
            status = ClassSessionStatus.CANCELED,
            canceledAt = canceledAt,
            cancelReason = reason,
            affectedReservationCount = affectedReservationCount,
        )
    }

    companion object {
        fun create(
            title: String,
            startsAt: Instant,
            endsAt: Instant,
            capacity: Int,
        ): ClassSession {
            if (title.isBlank()) {
                throw DomainException(ErrorCode.VALIDATION_FAILED, "title must not be blank.")
            }
            if (title.length > 150) {
                throw DomainException(ErrorCode.VALIDATION_FAILED, "title must be less than or equal to 150 characters.")
            }
            if (capacity < 1) {
                throw DomainException(ErrorCode.VALIDATION_FAILED, "capacity must be greater than or equal to 1.")
            }
            if (!startsAt.isBefore(endsAt)) {
                throw DomainException(ErrorCode.VALIDATION_FAILED, "startsAt must be before endsAt.")
            }

            return ClassSession(
                id = 0,
                title = title,
                startsAt = startsAt,
                endsAt = endsAt,
                capacity = capacity,
                reservedCount = 0,
                status = ClassSessionStatus.OPEN,
            )
        }
    }
}
