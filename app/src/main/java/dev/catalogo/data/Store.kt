package dev.catalogo.data

import android.util.AtomicFile
import dev.catalogo.core.AppState
import dev.catalogo.core.Auth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.io.File

/** The whole app state as one JSON file, written atomically on every change. */
class Store(dir: File) {
    private val file = AtomicFile(File(dir, "state.json"))
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()
    private val _state = MutableStateFlow(load())

    val state: StateFlow<AppState> = _state.asStateFlow()
    val value: AppState get() = _state.value

    /** Applies [block] and persists the result before publishing it. */
    suspend fun update(block: (AppState) -> AppState): AppState = mutex.withLock {
        val next = block(_state.value)
        if (next != _state.value) {
            write(next)
            _state.value = next
        }
        next
    }

    private fun load(): AppState {
        val loaded = runCatching {
            json.decodeFromString(AppState.serializer(), file.readFully().toString(Charsets.UTF_8))
        }.getOrNull()
        if (loaded != null) return loaded
        // Never overwrite an unreadable state silently: keep it aside.
        if (file.baseFile.exists()) file.baseFile.renameTo(File(file.baseFile.path + ".unreadable-${System.currentTimeMillis()}"))
        return AppState(deviceId = Auth.newId()).also(::write)
    }

    private fun write(state: AppState) {
        val out = file.startWrite()
        try {
            out.write(json.encodeToString(AppState.serializer(), state).toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
    }
}
