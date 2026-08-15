package dev.climbdesk.event.application

import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration

class OutboxPublisherPolicyTest {
    @Test
    fun `retry backoff count validation reports expected and actual values`() {
        assertThatThrownBy {
            OutboxPublisherPolicy(
                pollInterval = Duration.ofSeconds(1),
                maxPerTick = 20,
                maxAttempts = 5,
                confirmTimeout = Duration.ofSeconds(5),
                retryBackoffs = listOf(Duration.ofSeconds(5)),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("expected=4")
            .hasMessageContaining("actual=1")
            .hasMessageContaining("maxAttempts=5")
    }
}
