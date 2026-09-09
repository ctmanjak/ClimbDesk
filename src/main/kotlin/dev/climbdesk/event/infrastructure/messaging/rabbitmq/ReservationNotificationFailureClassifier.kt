package dev.climbdesk.event.infrastructure.messaging.rabbitmq

import com.fasterxml.jackson.core.JsonProcessingException
import dev.climbdesk.notification.application.ReservationNotificationProcessingException
import org.springframework.dao.RecoverableDataAccessException
import org.springframework.dao.TransientDataAccessException
import java.sql.SQLRecoverableException
import java.sql.SQLTransientException
import java.util.Collections
import java.util.IdentityHashMap

enum class ReservationNotificationFailureCategory(val retryable: Boolean, val summary: String) {
    PERMANENT_MESSAGE(false, "Invalid reservation confirmed message or metadata"),
    DATA_CONSISTENCY(false, "Reservation missing or identity mismatch"),
    TRANSIENT(true, "Temporary notification infrastructure failure"),
    UNKNOWN(true, "Unexpected notification processing failure"),
}

class ReservationNotificationFailureClassifier {
    fun classify(exception: Throwable): ReservationNotificationFailureCategory {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        val causes = generateSequence(exception) { it.cause }.takeWhile(seen::add).toList()
        return when {
            causes.any { it is InvalidReservationConfirmedMessageException || it is JsonProcessingException } ->
                ReservationNotificationFailureCategory.PERMANENT_MESSAGE
            causes.any { it is ReservationNotificationProcessingException } ->
                ReservationNotificationFailureCategory.DATA_CONSISTENCY
            causes.any {
                it is TransientDataAccessException || it is RecoverableDataAccessException ||
                    it is SQLTransientException || it is SQLRecoverableException
            } -> ReservationNotificationFailureCategory.TRANSIENT
            else -> ReservationNotificationFailureCategory.UNKNOWN
        }
    }
}
