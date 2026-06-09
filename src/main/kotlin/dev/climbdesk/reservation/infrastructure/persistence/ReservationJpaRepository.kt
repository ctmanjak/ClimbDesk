package dev.climbdesk.reservation.infrastructure.persistence

import dev.climbdesk.classsession.domain.ClassSessionStatus
import dev.climbdesk.pass.domain.MemberPassStatus
import dev.climbdesk.reservation.domain.ReservationStatus
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

interface ReservationJpaRepository : JpaRepository<ReservationJpaEntity, Long> {
    fun existsByMemberIdAndClassSessionIdAndStatus(
        memberId: Long,
        classSessionId: Long,
        status: ReservationStatus,
    ): Boolean

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select reservation from ReservationJpaEntity reservation where reservation.id = :id")
    fun findByIdForUpdate(@Param("id") id: Long): ReservationJpaEntity?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findAllByClassSessionIdAndStatusOrderByIdAsc(
        classSessionId: Long,
        status: ReservationStatus,
    ): List<ReservationJpaEntity>

    @Query(
        """
        select
            r.id as id,
            r.memberId as memberId,
            r.classSessionId as classSessionId,
            r.memberPassId as memberPassId,
            r.status as status,
            r.reservedAt as reservedAt,
            r.canceledAt as canceledAt,
            r.cancelReason as cancelReason,
            cs.id as classSessionSummaryId,
            cs.capacity as classSessionCapacity,
            cs.reservedCount as classSessionReservedCount,
            cs.status as classSessionStatus,
            mp.id as memberPassSummaryId,
            mp.remainingCount as memberPassRemainingCount,
            mp.status as memberPassStatus
        from ReservationJpaEntity r, ClassSessionJpaEntity cs, MemberPassJpaEntity mp
        where r.classSessionId = cs.id
          and r.memberPassId = mp.id
          and (:memberId is null or r.memberId = :memberId)
          and (:classSessionId is null or r.classSessionId = :classSessionId)
          and (:status is null or r.status = :status)
        order by r.reservedAt desc, r.id desc
        """,
        countQuery = """
        select count(r)
        from ReservationJpaEntity r
        where (:memberId is null or r.memberId = :memberId)
          and (:classSessionId is null or r.classSessionId = :classSessionId)
          and (:status is null or r.status = :status)
        """,
    )
    fun findReservationSummaries(
        memberId: Long?,
        classSessionId: Long?,
        status: ReservationStatus?,
        pageable: Pageable,
    ): Page<ReservationSummaryProjection>

    @Query(
        """
        select
            r.id as id,
            r.memberId as memberId,
            r.classSessionId as classSessionId,
            r.memberPassId as memberPassId,
            r.status as status,
            r.reservedAt as reservedAt,
            r.canceledAt as canceledAt,
            r.cancelReason as cancelReason,
            cs.id as classSessionSummaryId,
            cs.capacity as classSessionCapacity,
            cs.reservedCount as classSessionReservedCount,
            cs.status as classSessionStatus,
            mp.id as memberPassSummaryId,
            mp.remainingCount as memberPassRemainingCount,
            mp.status as memberPassStatus
        from ReservationJpaEntity r, ClassSessionJpaEntity cs, MemberPassJpaEntity mp
        where r.classSessionId = cs.id
          and r.memberPassId = mp.id
          and r.id = :reservationId
        """,
    )
    fun findReservationSummaryById(reservationId: Long): ReservationSummaryProjection?
}

interface ReservationSummaryProjection {
    val id: Long
    val memberId: Long
    val classSessionId: Long
    val memberPassId: Long
    val status: ReservationStatus
    val reservedAt: Instant
    val canceledAt: Instant?
    val cancelReason: dev.climbdesk.reservation.domain.ReservationCancelReason?
    val classSessionSummaryId: Long
    val classSessionCapacity: Int
    val classSessionReservedCount: Int
    val classSessionStatus: ClassSessionStatus
    val memberPassSummaryId: Long
    val memberPassRemainingCount: Int
    val memberPassStatus: MemberPassStatus
}
