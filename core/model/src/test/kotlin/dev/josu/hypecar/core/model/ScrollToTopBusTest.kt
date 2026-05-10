package dev.josu.hypecar.core.model

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Test

class ScrollToTopBusTest {
    @Test
    fun `request delivers the route to a subscriber`(): Unit = runBlocking {
        coroutineScope {
            val collected = async { ScrollToTopBus.events.take(1).toList() }
            // Replay-less SharedFlow — emit must happen after the collector subscribes.
            yield()
            ScrollToTopBus.request("latest")
            assertThat(collected.await()).containsExactly("latest")
        }
    }

    @Test
    fun `multiple requests are delivered in order`(): Unit = runBlocking {
        coroutineScope {
            val collected = async { ScrollToTopBus.events.take(3).toList() }
            yield()
            ScrollToTopBus.request("latest")
            ScrollToTopBus.request("popular")
            ScrollToTopBus.request("library")
            assertThat(collected.await()).containsExactly("latest", "popular", "library").inOrder()
        }
    }

    @Test
    fun `late subscriber misses prior requests`(): Unit = runBlocking {
        // Emit before any subscriber. The buffered event isn't a replay; it's
        // dropped once the buffer is empty when the next subscriber attaches.
        ScrollToTopBus.request("ghost")
        yield()

        coroutineScope {
            val collected = async { ScrollToTopBus.events.first() }
            yield()
            ScrollToTopBus.request("library")
            assertThat(collected.await()).isEqualTo("library")
        }
    }
}
