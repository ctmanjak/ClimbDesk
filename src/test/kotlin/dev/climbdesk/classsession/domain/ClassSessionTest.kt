package dev.climbdesk.classsession.domain

import dev.climbdesk.common.error.DomainException
import dev.climbdesk.common.error.ErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant

class ClassSessionTest {
    @Test
    fun `class session is created open with no reservations`() {
        val classSession = ClassSession.create(
            title = "Beginner Bouldering",
            startsAt = Instant.parse("2026-05-10T10:00:00Z"),
            endsAt = Instant.parse("2026-05-10T11:00:00Z"),
            capacity = 12,
        )

        assertThat(classSession.status).isEqualTo(ClassSessionStatus.OPEN)
        assertThat(classSession.reservedCount).isZero()
        assertThat(classSession.affectedReservationCount).isZero()
    }

    @ParameterizedTest
    @ValueSource(strings = ["", " "])
    fun `class session rejects blank title`(title: String) {
        assertThatThrownBy {
            ClassSession.create(
                title = title,
                startsAt = Instant.parse("2026-05-10T10:00:00Z"),
                endsAt = Instant.parse("2026-05-10T11:00:00Z"),
                capacity = 12,
            )
        }.isInstanceOf(DomainException::class.java)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.VALIDATION_FAILED)
    }

    @Test
    fun `class session rejects title over maximum length`() {
        assertThatThrownBy {
            ClassSession.create(
                title = "a".repeat(151),
                startsAt = Instant.parse("2026-05-10T10:00:00Z"),
                endsAt = Instant.parse("2026-05-10T11:00:00Z"),
                capacity = 12,
            )
        }.isInstanceOf(DomainException::class.java)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.VALIDATION_FAILED)
    }

    @ParameterizedTest
    @ValueSource(ints = [0, -1])
    fun `class session rejects non-positive capacity`(capacity: Int) {
        assertThatThrownBy {
            ClassSession.create(
                title = "Beginner Bouldering",
                startsAt = Instant.parse("2026-05-10T10:00:00Z"),
                endsAt = Instant.parse("2026-05-10T11:00:00Z"),
                capacity = capacity,
            )
        }.isInstanceOf(DomainException::class.java)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.VALIDATION_FAILED)
    }

    @ParameterizedTest
    @ValueSource(strings = ["2026-05-10T10:00:00Z", "2026-05-10T09:00:00Z"])
    fun `class session rejects time range without positive duration`(endsAt: String) {
        assertThatThrownBy {
            ClassSession.create(
                title = "Beginner Bouldering",
                startsAt = Instant.parse("2026-05-10T10:00:00Z"),
                endsAt = Instant.parse(endsAt),
                capacity = 12,
            )
        }.isInstanceOf(DomainException::class.java)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.VALIDATION_FAILED)
    }

    @Test
    fun `open class session can be canceled`() {
        val canceledAt = Instant.parse("2026-05-09T10:00:00Z")
        val classSession = classSession(reservedCount = 2)

        val canceled = classSession.cancel("Operational issue", canceledAt)

        assertThat(canceled.status).isEqualTo(ClassSessionStatus.CANCELED)
        assertThat(canceled.reservedCount).isZero()
        assertThat(canceled.canceledAt).isEqualTo(canceledAt)
        assertThat(canceled.cancelReason).isEqualTo("Operational issue")
        assertThat(canceled.affectedReservationCount).isEqualTo(2)
    }

    @Test
    fun `canceled class session cannot be canceled again`() {
        val canceled = classSession().cancel("Operational issue")

        assertThatThrownBy {
            canceled.cancel("Second request")
        }.isInstanceOf(DomainException::class.java)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.CLASS_SESSION_ALREADY_CANCELED)
    }

    @Test
    fun `closed class session can be canceled`() {
        val canceled = classSession(status = ClassSessionStatus.CLOSED).cancel("Operational issue")

        assertThat(canceled.status).isEqualTo(ClassSessionStatus.CANCELED)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "   "])
    fun `class session rejects blank cancellation reason`(reason: String) {
        assertThatThrownBy {
            classSession().cancel(reason)
        }.isInstanceOf(DomainException::class.java)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.VALIDATION_FAILED)
    }

    @Test
    fun `class session rejects cancellation reason over maximum length`() {
        assertThatThrownBy {
            classSession().cancel("a".repeat(501))
        }.isInstanceOf(DomainException::class.java)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.VALIDATION_FAILED)
    }

    @Test
    fun `canceled class session cannot reserve seat`() {
        val canceled = classSession().cancel("Operational issue")

        assertThatThrownBy {
            canceled.reserveSeat()
        }.isInstanceOf(DomainException::class.java)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.CLASS_SESSION_NOT_OPEN)
    }

    @Test
    fun `open class session reserves seat`() {
        val reserved = classSession().reserveSeat()

        assertThat(reserved.reservedCount).isEqualTo(1)
    }

    @Test
    fun `full class session cannot reserve seat`() {
        assertThatThrownBy {
            classSession(reservedCount = 2, capacity = 2).reserveSeat()
        }.isInstanceOf(DomainException::class.java)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.CLASS_SESSION_FULL)
    }

    @Test
    fun `class session cancels seat`() {
        val canceled = classSession(reservedCount = 1).cancelSeat()

        assertThat(canceled.reservedCount).isZero()
    }

    @Test
    fun `class session with no reservations cannot cancel seat`() {
        assertThatThrownBy {
            classSession().cancelSeat()
        }.isInstanceOf(DomainException::class.java)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.VALIDATION_FAILED)
    }

    private fun classSession(
        reservedCount: Int = 0,
        capacity: Int = 12,
        status: ClassSessionStatus = ClassSessionStatus.OPEN,
    ): ClassSession =
        ClassSession(
            id = 1,
            title = "Beginner Bouldering",
            startsAt = Instant.parse("2026-05-10T10:00:00Z"),
            endsAt = Instant.parse("2026-05-10T11:00:00Z"),
            capacity = capacity,
            reservedCount = reservedCount,
            status = status,
        )
}
