package dev.climbdesk.event.infrastructure.observability

import com.fasterxml.jackson.databind.ObjectMapper
import dev.climbdesk.event.infrastructure.messaging.rabbitmq.RabbitMqTopology
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.binder.MeterBinder
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import java.net.Authenticator
import java.net.PasswordAuthentication
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

class RabbitMqQueueMetrics(
    managementBaseUrl: String,
    username: String,
    password: String,
    virtualHost: String,
    private val objectMapper: ObjectMapper,
    requestTimeout: Duration,
    private val clock: Clock = Clock.systemUTC(),
) : MeterBinder {
    private val baseUrl = managementBaseUrl.trimEnd('/')
    private val encodedVhost = encode(virtualHost)
    private val requestTimeout = requestTimeout
    private val client = HttpClient.newBuilder()
        .connectTimeout(requestTimeout)
        .authenticator(object : Authenticator() {
            override fun getPasswordAuthentication() = PasswordAuthentication(username, password.toCharArray())
        })
        .build()
    private val snapshots = queueNames.associateWith { QueueSnapshot() }
    private val collectionHealthy = AtomicBoolean(true)
    private var backlogStartedAt: Instant? = null
    private lateinit var drainTimer: Timer

    override fun bindTo(registry: MeterRegistry) {
        snapshots.forEach { (queue, snapshot) ->
            val label = queueLabels.getValue(queue)
            Gauge.builder("climbdesk.messaging.rabbitmq.queue.depth", snapshot) { it.depth }
                .description("Current RabbitMQ queue depth, including ready and unacknowledged messages")
                .baseUnit("messages")
                .tag("queue", label)
                .register(registry)
            Gauge.builder("climbdesk.messaging.rabbitmq.queue.unacknowledged", snapshot) { it.unacknowledged }
                .description("Current RabbitMQ messages awaiting consumer acknowledgement")
                .baseUnit("messages")
                .tag("queue", label)
                .register(registry)
        }
        drainTimer = Timer.builder("climbdesk.messaging.rabbitmq.main.backlog.drain")
            .description("Observed time from a non-empty main queue until it next becomes empty")
            .publishPercentileHistogram()
            .register(registry)
    }

    @Scheduled(
        fixedDelayString = "\${climbdesk.messaging.rabbitmq.observability.sample-interval:5s}",
        initialDelayString = "\${climbdesk.messaging.rabbitmq.observability.sample-interval:5s}",
    )
    fun refresh() {
        try {
            snapshots.forEach { (queue, snapshot) -> snapshot.update(fetch(queue)) }
            observeDrain(snapshots.getValue(RabbitMqTopology.MAIN_QUEUE).depth.toLong())
            collectionHealthy.set(true)
        } catch (exception: Exception) {
            snapshots.values.forEach { it.unavailable() }
            if (collectionHealthy.getAndSet(false)) {
                logger.warn("RabbitMQ management metric collection unavailable: exceptionClass={}", exception.javaClass.simpleName)
            }
        }
    }

    private fun fetch(queue: String): BrokerQueueSnapshot {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/api/queues/$encodedVhost/${encode(queue)}"))
            .timeout(requestTimeout)
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) { "RabbitMQ management response ${response.statusCode()}" }
        val body = objectMapper.readTree(response.body())
        return BrokerQueueSnapshot(
            depth = body.path("messages").asLong(),
            unacknowledged = body.path("messages_unacknowledged").asLong(),
        )
    }

    private fun observeDrain(mainDepth: Long) {
        val now = clock.instant()
        if (mainDepth > 0) {
            backlogStartedAt = backlogStartedAt ?: now
        } else {
            backlogStartedAt?.let { drainTimer.record(Duration.between(it, now).coerceAtLeast(Duration.ZERO)) }
            backlogStartedAt = null
        }
    }

    private class QueueSnapshot {
        @Volatile var depth: Double = Double.NaN
        @Volatile var unacknowledged: Double = Double.NaN

        fun update(value: BrokerQueueSnapshot) {
            depth = value.depth.toDouble()
            unacknowledged = value.unacknowledged.toDouble()
        }

        fun unavailable() {
            depth = Double.NaN
            unacknowledged = Double.NaN
        }
    }

    private data class BrokerQueueSnapshot(val depth: Long, val unacknowledged: Long)

    private companion object {
        val logger = LoggerFactory.getLogger(RabbitMqQueueMetrics::class.java)
        val queueLabels = linkedMapOf(
            RabbitMqTopology.MAIN_QUEUE to "main",
            RabbitMqTopology.RETRY_QUEUE_5S to "retry_5s",
            RabbitMqTopology.RETRY_QUEUE_30S to "retry_30s",
            RabbitMqTopology.RETRY_QUEUE_2M to "retry_2m",
            RabbitMqTopology.DEAD_LETTER_QUEUE to "dlq",
        )
        val queueNames = queueLabels.keys

        fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
    }
}
