package dev.climbdesk.event.infrastructure.scheduling

import dev.climbdesk.event.application.OutboxPublisherPolicy
import dev.climbdesk.event.application.OutboxPublishUseCase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

class OutboxPublishSchedulerTest {
    @Test
    fun `one tick processes at most twenty events`() {
        var calls = 0
        val publisher = OutboxPublishUseCase { calls += 1; true }
        val scheduler = OutboxPublishScheduler(publisher, policy())

        scheduler.publishDueEvents()

        assertThat(calls).isEqualTo(20)
    }

    @Test
    fun `one tick stops immediately when there is no publishable event`() {
        var calls = 0
        val publisher = OutboxPublishUseCase { calls += 1; calls < 3 }
        val scheduler = OutboxPublishScheduler(publisher, policy())

        scheduler.publishDueEvents()

        assertThat(calls).isEqualTo(3)
    }

    @Test
    fun `one tick stops when publishing an event throws`() {
        var calls = 0
        val publisher = OutboxPublishUseCase {
            calls += 1
            if (calls == 2) {
                error("database status save failed")
            }
            true
        }
        val scheduler = OutboxPublishScheduler(publisher, policy())

        scheduler.publishDueEvents()

        assertThat(calls).isEqualTo(2)
    }

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
}
