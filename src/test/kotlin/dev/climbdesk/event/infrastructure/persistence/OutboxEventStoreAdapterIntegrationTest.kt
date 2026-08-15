package dev.climbdesk.event.infrastructure.persistence

import dev.climbdesk.event.domain.OutboxEventStatus
import dev.climbdesk.event.domain.OutboxPublishTarget
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
    properties = [
        "climbdesk.auth.jwt.secret=test-secret-that-is-long-enough-for-integration",
        "spring.datasource.url=jdbc:tc:postgresql:16-alpine:///outbox-publisher-store",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.jpa.hibernate.ddl-auto=validate",
    ],
)
class OutboxEventStoreAdapterIntegrationTest @Autowired constructor(
    private val outboxEventJpaRepository: OutboxEventJpaRepository,
    transactionManager: PlatformTransactionManager,
) {
    private val store = OutboxEventStoreAdapter(outboxEventJpaRepository)
    private val requiresNew =
        TransactionTemplate(transactionManager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        }

    @BeforeEach
    fun setUp() {
        outboxEventJpaRepository.deleteAll()
    }

    @AfterEach
    fun tearDown() {
        outboxEventJpaRepository.deleteAll()
    }

    @Test
    fun `claim selects RabbitMQ pending before due failed because null retry time sorts first`() {
        val now = Instant.parse("2026-08-15T00:00:00Z")
        save(status = OutboxEventStatus.FAILED, retryCount = 1, nextRetryAt = now.minusSeconds(10))
        val pending = save(status = OutboxEventStatus.PENDING)

        assertThat(claim(now)?.id).isEqualTo(pending.id)
    }

    @Test
    fun `claim orders due failed by retry time and id and limits the result to one`() {
        val now = Instant.parse("2026-08-15T00:00:00Z")
        val first = save(status = OutboxEventStatus.FAILED, retryCount = 2, nextRetryAt = now.minusSeconds(20))
        save(status = OutboxEventStatus.FAILED, retryCount = 1, nextRetryAt = now.minusSeconds(20))
        save(status = OutboxEventStatus.FAILED, retryCount = 1, nextRetryAt = now.minusSeconds(10))

        assertThat(claim(now)?.id).isEqualTo(first.id)
    }

    @Test
    fun `claim excludes non RabbitMQ published future retry and terminal failed rows`() {
        val now = Instant.parse("2026-08-15T00:00:00Z")
        save(status = OutboxEventStatus.PENDING, publishTarget = OutboxPublishTarget.NONE)
        save(status = OutboxEventStatus.PUBLISHED, publishedAt = now.minusSeconds(1))
        save(status = OutboxEventStatus.FAILED, retryCount = 1, nextRetryAt = now.plusSeconds(1))
        save(status = OutboxEventStatus.FAILED, retryCount = 5, nextRetryAt = null)

        assertThat(claim(now)).isNull()
    }

    @Test
    fun `claim returns null when there is no outbox row`() {
        assertThat(claim(Instant.parse("2026-08-15T00:00:00Z"))).isNull()
    }

    @Test
    fun `skip locked prevents two transactions from claiming the same row`() {
        val now = Instant.parse("2026-08-15T00:00:00Z")
        val event = save(status = OutboxEventStatus.PENDING)

        val claimedIds = claimConcurrently(now)

        assertThat(claimedIds).containsExactlyInAnyOrder(event.id, null)
    }

    @Test
    fun `skip locked lets two transactions claim different rows`() {
        val now = Instant.parse("2026-08-15T00:00:00Z")
        val first = save(status = OutboxEventStatus.PENDING)
        val second = save(status = OutboxEventStatus.PENDING)

        val claimedIds = claimConcurrently(now)

        assertThat(claimedIds).containsExactlyInAnyOrder(first.id, second.id)
    }

    private fun claim(now: Instant) = requiresNew.execute { store.claimNext(now, MAX_ATTEMPTS) }

    private fun claimConcurrently(now: Instant): List<Long?> {
        val bothClaimed = CountDownLatch(2)
        val releaseTransactions = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        return try {
            val futures = (1..2).map {
                executor.submit<Long?> {
                    requiresNew.execute {
                        val claimedId = store.claimNext(now, MAX_ATTEMPTS)?.id
                        bothClaimed.countDown()
                        check(releaseTransactions.await(5, TimeUnit.SECONDS))
                        claimedId
                    }
                }
            }
            check(bothClaimed.await(5, TimeUnit.SECONDS))
            releaseTransactions.countDown()
            futures.map { it.get(5, TimeUnit.SECONDS) }
        } finally {
            releaseTransactions.countDown()
            executor.shutdownNow()
        }
    }

    private fun save(
        status: OutboxEventStatus,
        publishTarget: OutboxPublishTarget = OutboxPublishTarget.RABBITMQ,
        retryCount: Int = 0,
        publishedAt: Instant? = null,
        nextRetryAt: Instant? = null,
    ): OutboxEventJpaEntity =
        outboxEventJpaRepository.saveAndFlush(
            OutboxEventJpaEntity(
                eventType = "ReservationConfirmedEvent",
                aggregateType = "Reservation",
                aggregateId = 101L,
                payload = "{}",
                status = status,
                publishTarget = publishTarget,
                schemaVersion = 1,
                retryCount = retryCount,
                occurredAt = Instant.parse("2026-08-14T00:00:00Z"),
                publishedAt = publishedAt,
                nextRetryAt = nextRetryAt,
            ),
        )

    private companion object {
        const val MAX_ATTEMPTS = 5
    }
}
