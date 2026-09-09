package dev.climbdesk.event.infrastructure.messaging.rabbitmq

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.climbdesk.notification.application.ReservationNotificationProcessingException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.dao.CannotAcquireLockException
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.QueryTimeoutException
import org.springframework.dao.RecoverableDataAccessException
import java.sql.SQLTransientConnectionException

class ReservationNotificationFailureClassifierTest {
    private val classifier = ReservationNotificationFailureClassifier()

    @Test
    fun `JSON and contract errors including wrapped causes are permanent`() {
        val json = runCatching { jacksonObjectMapper().readTree("{") }.exceptionOrNull()!!
        listOf(json, InvalidReservationConfirmedMessageException("contract")).forEach {
            assertThat(classifier.classify(RuntimeException(it)))
                .isEqualTo(ReservationNotificationFailureCategory.PERMANENT_MESSAGE)
        }
    }

    @Test
    fun `missing reservation and identity mismatch are data consistency errors`() {
        listOf("Reservation missing", "Identity mismatch").forEach {
            assertThat(classifier.classify(RuntimeException(ReservationNotificationProcessingException(it))))
                .isEqualTo(ReservationNotificationFailureCategory.DATA_CONSISTENCY)
        }
    }

    @Test
    fun `transient and recoverable data access and SQL causes are retryable`() {
        listOf(
            CannotAcquireLockException("lock"), QueryTimeoutException("timeout"),
            RecoverableDataAccessException("connection"), SQLTransientConnectionException("connection"),
        ).forEach {
            assertThat(classifier.classify(it)).isEqualTo(ReservationNotificationFailureCategory.TRANSIENT)
            assertThat(classifier.classify(RuntimeException(DataAccessResourceFailureException("wrapper", it))))
                .isEqualTo(ReservationNotificationFailureCategory.TRANSIENT)
        }
    }

    @Test
    fun `unknown errors are bounded retries and messages never determine classification`() {
        assertThat(classifier.classify(IllegalStateException("Invalid JSON Reservation missing lock timeout")))
            .isEqualTo(ReservationNotificationFailureCategory.UNKNOWN)
    }

    @Test
    fun `permanent errors take priority and cyclic causes terminate`() {
        val outer = RuntimeException(InvalidReservationConfirmedMessageException("invalid"))
        outer.cause!!.initCause(outer)
        assertThat(classifier.classify(outer)).isEqualTo(ReservationNotificationFailureCategory.PERMANENT_MESSAGE)
    }
}
