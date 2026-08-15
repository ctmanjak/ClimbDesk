package dev.climbdesk.event.application

import dev.climbdesk.event.domain.OutboxEvent
import dev.climbdesk.event.domain.OutboxEventStatus
import dev.climbdesk.event.domain.OutboxPublishTarget
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

class PollingOutboxPublisherTest {
    @Test
    fun `successful publish records published state only after the outbound result succeeds`() {
        val now = Instant.parse("2026-08-15T00:00:00Z")
        val store = FakeStore(event(nextRetryAt = now.minusSeconds(1), lastError = "previous failure", retryCount = 2))
        val publisher = publisher(store, publishResult = PublishResult.Success)

        assertThat(publisher.publishNext(now)).isTrue()

        assertThat(store.event!!.status).isEqualTo(OutboxEventStatus.PUBLISHED)
        assertThat(store.event!!.publishedAt).isEqualTo(now)
        assertThat(store.event!!.retryCount).isEqualTo(2)
        assertThat(store.event!!.nextRetryAt).isNull()
        assertThat(store.event!!.lastError).isNull()
    }

    @ParameterizedTest
    @ValueSource(strings = ["confirm NACK", "mandatory return", "confirm timeout", "connection error"])
    fun `broker failure outcomes remain failed and due for retry`(reason: String) {
        val now = Instant.parse("2026-08-15T00:00:00Z")
        val store = FakeStore(event())
        val publisher = publisher(store, publishResult = PublishResult.Failure(reason))

        assertThat(publisher.publishNext(now)).isTrue()

        assertThat(store.event!!.status).isEqualTo(OutboxEventStatus.FAILED)
        assertThat(store.event!!.publishedAt).isNull()
        assertThat(store.event!!.retryCount).isEqualTo(1)
        assertThat(store.event!!.nextRetryAt).isEqualTo(now.plusSeconds(5))
        assertThat(store.event!!.lastError).isEqualTo(reason)
    }

    @Test
    fun `publisher applies every backoff and makes the fifth failure terminal`() {
        val start = Instant.parse("2026-08-15T00:00:00Z")
        val store = FakeStore(event())
        val publisher = publisher(store, publishResult = PublishResult.Failure("broker unavailable"))
        val attempts =
            listOf(
                start to start.plusSeconds(5),
                start.plusSeconds(5) to start.plusSeconds(35),
                start.plusSeconds(35) to start.plusSeconds(155),
                start.plusSeconds(155) to start.plusSeconds(755),
                start.plusSeconds(755) to null,
            )

        attempts.forEachIndexed { index, (now, expectedNextRetryAt) ->
            assertThat(publisher.publishNext(now)).isTrue()
            assertThat(store.event!!.retryCount).isEqualTo(index + 1)
            assertThat(store.event!!.nextRetryAt).isEqualTo(expectedNextRetryAt)
        }

        assertThat(store.event!!.status).isEqualTo(OutboxEventStatus.FAILED)
        assertThat(store.event!!.retryCount).isEqualTo(5)
        assertThat(store.event!!.nextRetryAt).isNull()
    }

    @Test
    fun `mapping failure is recorded without payload or exception message`() {
        val now = Instant.parse("2026-08-15T00:00:00Z")
        val store = FakeStore(event())
        val publisher =
            PollingOutboxPublisher(
                outboxEventStore = store,
                outboxMessageMapper = OutboxMessageMapper { error("secret-payload") },
                outboundMessagePublisher = OutboundMessagePublisher { _, _ -> PublishResult.Success },
                policy = policy(),
            )

        assertThat(publisher.publishNext(now)).isTrue()

        assertThat(store.event!!.lastError).isEqualTo("Outbox message mapping failed: IllegalStateException")
        assertThat(store.event!!.lastError).doesNotContain("secret-payload")
    }

    @Test
    fun `unsupported RabbitMQ contract becomes terminal failed immediately`() {
        val store = FakeStore(event())
        val publisher =
            PollingOutboxPublisher(
                outboxEventStore = store,
                outboxMessageMapper = OutboxMessageMapper {
                    throw UnsupportedOutboxContractException("Unsupported Outbox event type: UnknownEvent")
                },
                outboundMessagePublisher = OutboundMessagePublisher { _, _ -> PublishResult.Success },
                policy = policy(),
            )

        assertThat(publisher.publishNext(Instant.parse("2026-08-15T00:00:00Z"))).isTrue()

        assertThat(store.event!!.status).isEqualTo(OutboxEventStatus.FAILED)
        assertThat(store.event!!.retryCount).isEqualTo(5)
        assertThat(store.event!!.nextRetryAt).isNull()
        assertThat(store.event!!.lastError).isEqualTo("Unsupported Outbox event type: UnknownEvent")
    }

    @Test
    fun `last error is normalized and truncated to the database limit`() {
        val store = FakeStore(event())
        val reason = "  failure\n" + "x".repeat(1_100)
        val publisher = publisher(store, publishResult = PublishResult.Failure(reason))

        publisher.publishNext(Instant.parse("2026-08-15T00:00:00Z"))

        assertThat(store.event!!.lastError).hasSize(1_000)
        assertThat(store.event!!.lastError).doesNotContain("\n")
    }

    @Test
    fun `empty claim stops the current scheduler loop`() {
        val store = FakeStore(null)
        val publisher = publisher(store, publishResult = PublishResult.Success)

        assertThat(publisher.publishNext(Instant.parse("2026-08-15T00:00:00Z"))).isFalse()
        assertThat(store.saved).isZero()
    }

    @Test
    fun `one event method declares a requires new transaction boundary`() {
        val annotation =
            PollingOutboxPublisher::class.java
                .getDeclaredMethod("publishNext", Instant::class.java)
                .getAnnotation(Transactional::class.java)

        assertThat(annotation.propagation).isEqualTo(Propagation.REQUIRES_NEW)
    }

    private fun publisher(
        store: FakeStore,
        publishResult: PublishResult,
    ): PollingOutboxPublisher =
        PollingOutboxPublisher(
            outboxEventStore = store,
            outboxMessageMapper = OutboxMessageMapper {
                OutboundMessage(
                    messageId = it.id.toString(),
                    eventType = "reservation.confirmed",
                    schemaVersion = 1,
                    producer = "climbdesk",
                    body = byteArrayOf(1),
                )
            },
            outboundMessagePublisher = OutboundMessagePublisher { _, _ -> publishResult },
            policy = policy(),
        )

    private fun policy() =
        OutboxPublisherPolicy(
            pollInterval = Duration.ofSeconds(1),
            maxPerTick = 20,
            maxAttempts = 5,
            confirmTimeout = Duration.ofSeconds(5),
            retryBackoffs =
                listOf(
                    Duration.ofSeconds(5),
                    Duration.ofSeconds(30),
                    Duration.ofMinutes(2),
                    Duration.ofMinutes(10),
                ),
        )

    private fun event(
        nextRetryAt: Instant? = null,
        lastError: String? = null,
        retryCount: Int = 0,
    ) =
        OutboxEvent(
            id = 101L,
            eventType = "ReservationConfirmedEvent",
            aggregateType = "Reservation",
            aggregateId = 201L,
            payload = "{}",
            status = if (retryCount == 0) OutboxEventStatus.PENDING else OutboxEventStatus.FAILED,
            publishTarget = OutboxPublishTarget.RABBITMQ,
            schemaVersion = 1,
            retryCount = retryCount,
            occurredAt = Instant.parse("2026-08-14T00:00:00Z"),
            nextRetryAt = nextRetryAt,
            lastError = lastError,
        )

    private class FakeStore(
        var event: OutboxEvent?,
    ) : OutboxEventStore {
        var saved: Int = 0

        override fun claimNext(
            now: Instant,
            maxAttempts: Int,
        ): OutboxEvent? = event

        override fun save(event: OutboxEvent): OutboxEvent {
            this.event = event
            saved += 1
            return event
        }
    }
}
