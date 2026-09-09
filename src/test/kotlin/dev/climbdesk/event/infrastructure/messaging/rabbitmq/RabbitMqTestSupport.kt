package dev.climbdesk.event.infrastructure.messaging.rabbitmq

import org.awaitility.Awaitility.await
import org.awaitility.core.ConditionTimeoutException
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.testcontainers.containers.RabbitMQContainer
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

internal fun RabbitTemplate.awaitAmqpReady(
    rabbitMq: RabbitMQContainer,
    timeout: Duration = Duration.ofMinutes(2),
) {
    val lastFailure = AtomicReference<Throwable>()
    try {
        await().atMost(timeout).pollInterval(Duration.ofMillis(200)).until {
            val result = runCatching { execute { channel -> channel.isOpen } == true }
            result.exceptionOrNull()?.let(lastFailure::set)
            result.getOrDefault(false)
        }
    } catch (timeoutFailure: ConditionTimeoutException) {
        throw AssertionError(
            readinessDiagnostics(rabbitMq, timeout, lastFailure.get()),
            lastFailure.get() ?: timeoutFailure,
        )
    }
}

private fun readinessDiagnostics(
    rabbitMq: RabbitMQContainer,
    timeout: Duration,
    lastFailure: Throwable?,
): String =
    buildString {
        appendLine("RabbitMQ AMQP did not become ready within ${timeout.toMillis()} ms.")
        appendLine("Last AMQP error: ${lastFailure?.summary() ?: "<none>"}")
        appendLine("Container state: ${rabbitMq.containerState()}")
        appendLine("RabbitMQ logs (last 200 lines):")
        append(rabbitMq.logTail())
    }

private fun RabbitMQContainer.containerState(): String =
    runCatching {
        val state = dockerClient.inspectContainerCmd(containerId).exec().state
        "id=$containerId, status=${state.status}, running=${state.running}, paused=${state.paused}, " +
            "restarting=${state.restarting}, oomKilled=${state.oomKilled}, dead=${state.dead}, " +
            "exitCode=${state.exitCodeLong}, error=${state.error}"
    }.getOrElse { "<unavailable: ${it.summary()}>" }

private fun RabbitMQContainer.logTail(): String =
    runCatching {
        logs.lineSequence().toList().takeLast(200).joinToString("\n").ifBlank { "<empty>" }
    }.getOrElse { "<unavailable: ${it.summary()}>" }

private fun Throwable.summary(): String = "${javaClass.simpleName}: ${message ?: "<no message>"}"
