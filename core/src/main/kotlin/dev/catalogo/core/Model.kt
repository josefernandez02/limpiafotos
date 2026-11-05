package dev.catalogo.core

import kotlinx.serialization.Serializable

@Serializable
enum class Role { PARENT, CHILD }

/** A phone taking part. [secret] is the pairing key (children only). */
@Serializable
data class Device(
    val id: String,
    val name: String,
    val prefix: String,
    val secret: String = "",
)

/**
 * One catalogued article, identified by its number (shared by every device).
 * Once [released], it stays here until every device in [pendingRelease] has
 * confirmed deleting its album; only then the number becomes free again.
 */
@Serializable
data class Article(
    val number: Int,
    val createdAt: Long,
    val released: Boolean = false,
    val pendingRelease: Set<String> = emptySet(),
    val delivered: Map<String, Int> = emptyMap(),
)

/** Photos being taken with the system camera for [deviceId]'s album of [number]. */
@Serializable
data class Session(val number: Int, val deviceId: String, val startedAt: Long)

@Serializable
data class ParentState(
    val self: Device,
    val digits: Int = 4,
    val children: List<Device> = emptyList(),
    val articles: List<Article> = emptyList(),
    val session: Session? = null,
    val configVersion: Long = 1,
)

@Serializable
data class ChildState(
    val self: Device,
    val parentId: String,
    val secret: String,
    val digits: Int,
    val configVersion: Long = 0,
    /** Numbers released on this device that the parent has not acknowledged yet. */
    val pendingReleases: Set<Int> = emptySet(),
)

@Serializable
data class AppState(
    val deviceId: String,
    val baseFolder: String = BASE_FOLDERS.first(),
    val parent: ParentState? = null,
    val child: ChildState? = null,
    /** Chosen as secondary but not paired yet. */
    val secondary: Boolean = false,
) {
    val role: Role? get() = when {
        parent != null -> Role.PARENT
        child != null || secondary -> Role.CHILD
        else -> null
    }

    companion object {
        val BASE_FOLDERS = listOf("Pictures", "DCIM")
    }
}
