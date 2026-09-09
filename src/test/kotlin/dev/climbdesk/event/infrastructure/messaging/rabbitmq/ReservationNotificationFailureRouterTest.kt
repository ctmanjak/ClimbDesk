package dev.climbdesk.event.infrastructure.messaging.rabbitmq

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.amqp.core.Message
import org.springframework.amqp.core.MessageDeliveryMode
import org.springframework.amqp.core.MessageProperties
import java.math.BigInteger
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ReservationNotificationFailureRouterTest {
    private val now = Instant.parse("2026-09-09T00:00:00Z")
    private val router = ReservationNotificationFailureRouter(Clock.fixed(now, ZoneOffset.UTC))
    private val error = IllegalStateException("SELECT secret FROM users sensitive@example.com " + "x".repeat(2000))

    @Test
    fun `absent retry count is zero and broker integer types are supported`() {
        assertThat(router.retryCount(message())).isZero()
        listOf(1.toByte(), 1.toShort(), 1, 1L).forEach { count ->
            assertThat(router.retryCount(message(count))).isEqualTo(1)
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 2, 3, 4, Int.MAX_VALUE])
    fun `retry count selects production queue and increments only outbound copy`(count: Int) {
        val original = message(count)
        val route = router.route(original, ReservationNotificationFailureCategory.TRANSIENT, error)
        assertThat(route.exchange).isEqualTo(if (count < 3) RabbitMqTopology.RETRY_EXCHANGE else RabbitMqTopology.DEAD_LETTER_EXCHANGE)
        assertThat(route.routingKey).isEqualTo(if (count < 3) RabbitMqTopology.retryQueues[count].name else RabbitMqTopology.DEAD_LETTER_QUEUE)
        assertThat(route.message.messageProperties.headers["x-retry-count"]).isEqualTo(if (count < 3) count + 1 else count)
        assertThat(original.messageProperties.headers["x-retry-count"]).isEqualTo(count)
    }

    @Test
    fun `unknown failures use the same bounded routing`() {
        for (count in 0..3) {
            assertThat(router.route(message(count), ReservationNotificationFailureCategory.UNKNOWN, error).routingKey)
                .isEqualTo(if (count < 3) RabbitMqTopology.retryQueues[count].name else RabbitMqTopology.DEAD_LETTER_QUEUE)
        }
    }

    @Test
    fun `permanent and consistency failures always go directly to DLQ`() {
        listOf(ReservationNotificationFailureCategory.PERMANENT_MESSAGE, ReservationNotificationFailureCategory.DATA_CONSISTENCY).forEach { category ->
            listOf(0, 1, 2, 3, 99).forEach { count ->
                val route = router.route(message(count), category, error)
                assertThat(route.routingKey).isEqualTo(RabbitMqTopology.DEAD_LETTER_QUEUE)
                assertThat(route.message.messageProperties.headers["x-retry-count"]).isEqualTo(count)
            }
        }
    }

    @Test
    fun `poison retry metadata is never reset to zero`() {
        listOf(-1, -1L, "1", 1.0, true, Int.MAX_VALUE.toLong() + 1, Long.MAX_VALUE, BigInteger.ONE).forEach { count ->
            val original = message(count)
            assertThatThrownBy { router.retryCount(original) }.isInstanceOf(InvalidReservationConfirmedMessageException::class.java)
            val route = router.route(original, ReservationNotificationFailureCategory.TRANSIENT, error)
            assertThat(route.routingKey).isEqualTo(RabbitMqTopology.DEAD_LETTER_QUEUE)
            assertThat(route.message.messageProperties.headers["x-failure-category"]).isEqualTo("PERMANENT_MESSAGE")
            assertThat(route.message.messageProperties.headers["x-retry-count"]).isEqualTo(count)
        }
    }

    @Test
    fun `contract body original routing and first failure survive while last failure changes`() {
        val original = message().apply {
            messageProperties.setHeader("x-death", listOf(mapOf("count" to 900L)))
            messageProperties.setHeader("untrusted-payload", "sensitive@example.com")
        }
        val first = router.route(original, ReservationNotificationFailureCategory.TRANSIENT, error).message
        val later = ReservationNotificationFailureRouter(Clock.fixed(now.plusSeconds(5), ZoneOffset.UTC))
        first.messageProperties.receivedExchange = RabbitMqTopology.RETRY_EXCHANGE
        val second = later.route(first, ReservationNotificationFailureCategory.UNKNOWN, error).message
        assertThat(second.body).isEqualTo(original.body)
        assertThat(second.messageProperties.messageId).isEqualTo("101")
        assertThat(second.messageProperties.type).isEqualTo("reservation.confirmed")
        assertThat(second.messageProperties.contentType).isEqualTo(MessageProperties.CONTENT_TYPE_JSON)
        assertThat(second.messageProperties.deliveryMode).isEqualTo(MessageDeliveryMode.PERSISTENT)
        val headers = second.messageProperties.headers
        assertThat(headers).containsEntry("x-schema-version", 1).containsEntry("x-producer", "climbdesk")
            .containsEntry("x-retry-count", 2).containsEntry("x-original-exchange", "original.exchange")
            .containsEntry("x-original-routing-key", "original.key").containsEntry("x-original-queue", "original.queue")
            .containsEntry("x-first-failed-at", now.toString()).containsEntry("x-last-failed-at", now.plusSeconds(5).toString())
            .containsEntry("x-failure-category", "UNKNOWN").containsEntry("x-exception-class", "IllegalStateException")
            .doesNotContainKeys("x-death", "untrusted-payload")
        assertThat(headers.toString()).doesNotContain("sensitive", "SELECT", "stackTrace", "xxx")
        ReservationNotificationFailureCategory.entries.forEach {
            assertThat(it.summary.length).isLessThanOrEqualTo(ReservationNotificationFailureRouter.MAX_ERROR_LENGTH)
        }
        assertThat(headers["x-error-summary"]).isEqualTo("Unexpected notification processing failure")
    }

    private fun message(count: Any? = null) = Message("original body".toByteArray(), MessageProperties().apply {
        messageId = "101"
        type = "reservation.confirmed"
        receivedExchange = "original.exchange"
        receivedRoutingKey = "original.key"
        consumerQueue = "original.queue"
        setHeader("x-schema-version", 1)
        setHeader("x-producer", "climbdesk")
        if (count != null) setHeader("x-retry-count", count)
    })
}
