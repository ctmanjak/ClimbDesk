package dev.climbdesk.classsession.application

import dev.climbdesk.classsession.domain.ClassSession
import dev.climbdesk.classsession.domain.ClassSessionStatus
import java.time.Instant

data class ClassSessionResult(
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
) {
    companion object {
        fun from(classSession: ClassSession): ClassSessionResult =
            ClassSessionResult(
                id = classSession.id,
                title = classSession.title,
                startsAt = classSession.startsAt,
                endsAt = classSession.endsAt,
                capacity = classSession.capacity,
                reservedCount = classSession.reservedCount,
                status = classSession.status,
                createdAt = requireNotNull(classSession.createdAt) { "ClassSession must have createdAt." },
                canceledAt = classSession.canceledAt,
                affectedReservationCount = classSession.affectedReservationCount,
            )
    }
}
