package boot


import io.github.tccl2785.tools.load.dual.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class DualPhaseOrchestratorSuspendTest {

    @Test
    fun `launchSuspend executes regular and suspend methods in order`() = runTest {
        MixedInitializer.events.clear()

        val orchestrator = DualPhaseOrchestrator()
        orchestrator.register(
            LoadItem(
                MixedInitializer::class.java,
                null,
                LoadMode.NONE,
                true
            )
        )

        orchestrator.launchSuspend()

        assertEquals(
            listOf("suspend-1-start", "suspend-1-end", "regular-2"),
            MixedInitializer.events
        )
    }

    @Test
    fun `Kotlin object can be supplied explicitly`() = runTest {
        ExplicitInitializer.called = false

        val orchestrator = DualPhaseOrchestrator()
        orchestrator.register(
            LoadItem(
                ExplicitInitializer::class.java,
                ExplicitInitializer,
                LoadMode.NONE,
                true
            )
        )

        orchestrator.launchSuspend()

        assertEquals(true, ExplicitInitializer.called)
    }

    @Test
    fun `launch rejects suspend methods before execution`() {
        MixedInitializer.events.clear()

        val orchestrator = DualPhaseOrchestrator()
        orchestrator.register(
            LoadItem(
                MixedInitializer::class.java,
                null,
                LoadMode.NONE,
                true
            )
        )

        assertThrows(IllegalArgumentException::class.java) {
            orchestrator.launch()
        }

        assertEquals(emptyList<String>(), MixedInitializer.events)
    }

    @Test
    fun `cancellation is not ignored`() {
        assertThrows(CancellationException::class.java) {
            runTest {
                val orchestrator = DualPhaseOrchestrator()
                orchestrator.register(
                    LoadItem(
                        CancellingInitializer::class.java,
                        null,
                        LoadMode.NONE,
                        true
                    )
                )

                orchestrator.launchSuspend()
            }
        }
    }

    object MixedInitializer {
        val events = mutableListOf<String>()

        @LoadStage(order = 1)
        private suspend fun first(context: InitializationContext) {
            events += "suspend-1-start"
            delay(10)
            events += "suspend-1-end"
        }

        @LoadStage(order = 2)
        private fun second(context: InitializationContext) {
            events += "regular-2"
        }
    }

    object ExplicitInitializer {
        var called = false

        @LoadStage(order = 1)
        private suspend fun initialize(context: InitializationContext) {
            called = true
        }
    }

    object CancellingInitializer {
        @LoadStage(order = 1, ignoreException = true)
        private suspend fun initialize(context: InitializationContext) {
            throw CancellationException("cancelled")
        }
    }
}