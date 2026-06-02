package dev.climbdesk.eventoutbox.infrastructure.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import dev.climbdesk.reservation.domain.ReservationConfirmedEvent
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.dao.DataIntegrityViolationException
import java.time.Instant

class OutboxEventPersistenceAdapterTest {
    @Test
    fun `record propagates persistence failure`() {
        val repository = mock(OutboxEventJpaRepository::class.java)
        val persistenceFailure = DataIntegrityViolationException("outbox save failed")
        `when`(repository.saveAndFlush(any(OutboxEventJpaEntity::class.java))).thenThrow(persistenceFailure)
        val adapter = OutboxEventPersistenceAdapter(repository, ObjectMapper().findAndRegisterModules())

        assertThatThrownBy {
            adapter.record(
                ReservationConfirmedEvent(
                    reservationId = 101L,
                    memberId = 201L,
                    classSessionId = 301L,
                    memberPassId = 401L,
                    occurredAt = Instant.parse("2026-06-02T00:00:00Z"),
                ),
            )
        }.isSameAs(persistenceFailure)
    }
}
