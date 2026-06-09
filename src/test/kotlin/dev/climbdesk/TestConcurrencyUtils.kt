package dev.climbdesk

import org.assertj.core.api.Assertions.assertThat
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object TestConcurrencyUtils {
    fun <T> runConcurrently(vararg requests: () -> T): List<T> {
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(requests.size)

        return try {
            val futures = requests.map { request ->
                executor.submit(
                    Callable {
                        check(start.await(5, TimeUnit.SECONDS))
                        request()
                    },
                )
            }
            start.countDown()
            futures.map { it.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue()
        }
    }
}
