package dev.josu.hypecar.core.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OfflineFavoritesSyncWorkerDiTest {
    @Test
    fun `androidx hilt compiler generates the worker assisted factory and map module`() {
        val generatedFactory = Class.forName(
            "dev.josu.hypecar.core.data.repository.OfflineFavoritesSyncWorker_AssistedFactory",
        )
        val generatedModule = Class.forName(
            "dev.josu.hypecar.core.data.repository.OfflineFavoritesSyncWorker_HiltModule",
        )

        assertThat(generatedFactory.interfaces.map(Class<*>::getName))
            .contains("androidx.hilt.work.WorkerAssistedFactory")
        assertThat(generatedModule.isInterface).isTrue()
        assertThat(generatedModule.declaredMethods.map { it.name }).containsExactly("bind")
    }
}
