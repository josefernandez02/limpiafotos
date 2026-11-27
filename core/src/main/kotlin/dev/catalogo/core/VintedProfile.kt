package dev.catalogo.core

/** The profile link of a Vinted account. */
object VintedProfile {
    /** (member id, user name) from a profile link such as vinted.es/member/123456-shiorawear. */
    fun parse(link: String): Pair<String, String>? {
        val m = Regex("(?:member|members)/(\\d{3,})(?:-([A-Za-z0-9_.\\-]+))?").find(link.trim()) ?: return null
        return m.groupValues[1] to m.groupValues[2]
    }
}
