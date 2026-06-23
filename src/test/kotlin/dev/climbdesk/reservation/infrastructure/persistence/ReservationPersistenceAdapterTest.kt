package dev.climbdesk.reservation.infrastructure.persistence

import dev.climbdesk.common.error.ApplicationException
import dev.climbdesk.common.error.ErrorCode
import dev.climbdesk.reservation.domain.Reservation
import dev.climbdesk.reservation.domain.ReservationStatus
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.hibernate.exception.ConstraintViolationException
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.dao.DataIntegrityViolationException
import java.sql.SQLException
import java.time.Instant

class ReservationPersistenceAdapterTest {
    @Test
    fun `save maps confirmed reservation unique constraint name to duplicate reservation`() {
        val dataIntegrityViolation = DataIntegrityViolationException(
            "could not execute statement",
            constraintViolation("uk_reservations_confirmed_member_class"),
        )
        val adapter = adapterThrowing(dataIntegrityViolation)

        assertThatThrownBy { adapter.save(reservation()) }
            .isInstanceOf(ApplicationException::class.java)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.DUPLICATE_RESERVATION)
    }

    @Test
    fun `save maps confirmed reservation unique column signature to duplicate reservation`() {
        val dataIntegrityViolation = DataIntegrityViolationException(
            "duplicate key value violates unique constraint on reservations member_id class_session_id",
        )
        val adapter = adapterThrowing(dataIntegrityViolation)

        assertThatThrownBy { adapter.save(reservation()) }
            .isInstanceOf(ApplicationException::class.java)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.DUPLICATE_RESERVATION)
    }

    @Test
    fun `save does not map unrelated reservation unique violation to duplicate reservation`() {
        val dataIntegrityViolation = DataIntegrityViolationException(
            "duplicate key value violates unique constraint on reservations member_pass_id",
        )
        val adapter = adapterThrowing(dataIntegrityViolation)

        assertThatThrownBy { adapter.save(reservation()) }
            .isSameAs(dataIntegrityViolation)
    }

    @Test
    fun `save does not map unrelated constraint violation to duplicate reservation`() {
        val constraintViolation = constraintViolation("ck_reservations_status")
        val adapter = adapterThrowing(constraintViolation)

        assertThatThrownBy { adapter.save(reservation()) }
            .isSameAs(constraintViolation)
    }

    private fun adapterThrowing(exception: RuntimeException): ReservationPersistenceAdapter {
        val repository = mock(ReservationJpaRepository::class.java)
        `when`(repository.saveAndFlush(any(ReservationJpaEntity::class.java))).thenThrow(exception)
        return ReservationPersistenceAdapter(repository)
    }

    private fun constraintViolation(constraintName: String): ConstraintViolationException =
        ConstraintViolationException(
            "constraint violation",
            SQLException("constraint violation"),
            constraintName,
        )

    private fun reservation(): Reservation =
        Reservation(
            memberId = 1,
            classSessionId = 2,
            memberPassId = 3,
            status = ReservationStatus.CONFIRMED,
            reservedAt = Instant.parse("2026-05-01T00:00:00Z"),
        )
}
