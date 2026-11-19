package dev.catalogo.core

/**
 * A re-listed article goes up again with new photos, so its title and hashtags must change too (the same
 * words again would be the same listing). The AI writes them; this says whether they differ enough.
 */
object Relisting {
    /** Words in the new title that the old one did not have: one swapped word (a colour, a synonym) is too little, a reorder is nothing. */
    const val MIN_WORDS = 2

    /** Hashtags that were not in the old set: the brand and the garment stay, the rest is other searches. */
    const val MIN_TAGS = 3

    private fun words(title: String): Set<String> =
        java.text.Normalizer.normalize(title.lowercase(), java.text.Normalizer.Form.NFD)
            .split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.toSet()

    fun titleChanged(old: String, new: String): Boolean = new.isNotBlank() && (words(new) - words(old)).size >= MIN_WORDS

    fun hashtagsChanged(old: List<String>, new: List<String>): Boolean {
        val before = old.map { it.lowercase() }.toSet()
        return new.map { it.lowercase() }.toSet().count { it !in before } >= MIN_TAGS
    }

    /** What is still repeated, in plain words (empty = fine). A device without a new title counts as repeating it. */
    fun repeated(avoid: Relist, titles: Map<String, String>, hashtags: List<String>): List<String> = buildList {
        if (avoid.titles.any { (id, old) -> !titleChanged(old, titles[id].orEmpty()) }) add("título")
        if (avoid.hashtags.isNotEmpty() && !hashtagsChanged(avoid.hashtags, hashtags)) add("hashtags")
    }
}
