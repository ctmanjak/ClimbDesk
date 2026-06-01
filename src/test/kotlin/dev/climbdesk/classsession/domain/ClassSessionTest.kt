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
}
