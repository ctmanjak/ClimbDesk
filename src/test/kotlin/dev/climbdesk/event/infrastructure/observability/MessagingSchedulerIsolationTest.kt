package dev.climbdesk.event.infrastructure.observability

import dev.climbdesk.event.infrastructure.scheduling.OUTBOX_PUBLISH_SCHEDULER_BEAN_NAME
import dev.climbdesk.event.infrastructure.scheduling.OutboxPublishScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.scheduling.annotation.Scheduled

class MessagingSchedulerIsolationTest {
    @Test
    fun `queue sampling and outbox publishing use distinct schedulers`() {
        val queueScheduler = scheduledOn(RabbitMqQueueMetrics::class.java, "refresh")
        val outboxScheduler = scheduledOn(OutboxPublishScheduler::class.java, "publishDueEvents")

        assertThat(queueScheduler).isEqualTo(RABBIT_MQ_QUEUE_METRICS_SCHEDULER_BEAN_NAME)
        assertThat(outboxScheduler).isEqualTo(OUTBOX_PUBLISH_SCHEDULER_BEAN_NAME)
        assertThat(queueScheduler).isNotEqualTo(outboxScheduler)
    }

    private fun scheduledOn(type: Class<*>, method: String): String =
        type.getDeclaredMethod(method).getAnnotation(Scheduled::class.java).scheduler
}
