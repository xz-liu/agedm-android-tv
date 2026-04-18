package io.agedm.tv.data

private const val AGE_PROVIDER_ID = "age"
val SUPPLEMENTAL_PROVIDER_IDS: Set<String> = linkedSetOf("aafun", "dm84")

fun List<EpisodeSource>.mergeDistinctSources(extraSources: List<EpisodeSource>): List<EpisodeSource> {
    if (extraSources.isEmpty()) return this
    val seen = mapTo(linkedSetOf()) { it.key }
    val appended = extraSources.filter { seen.add(it.key) }
    return this + appended
}

fun List<EpisodeSource>.orderedByPriority(priority: List<String>): List<EpisodeSource> {
    val priorityMap = priority
        .mapIndexed { index, provider -> normalizeSourceProvider(provider) to index }
        .toMap()

    return withIndex()
        .sortedWith(
            compareBy<IndexedValue<EpisodeSource>>(
                { if (it.value.isAgeSource()) 0 else 1 },
                { priorityMap[normalizeSourceProvider(it.value.providerName)] ?: Int.MAX_VALUE / 4 },
            ).thenBy { it.index },
        )
        .map { it.value }
}

fun EpisodeSource.isAgeSource(): Boolean {
    return resolver == SourceResolver.AGE_PARSER || normalizeSourceProvider(providerName) == AGE_PROVIDER_ID
}

fun EpisodeSource.isExternalSource(): Boolean = !isAgeSource()

fun List<EpisodeSource>.loadedSupplementalProviders(): Set<String> {
    return filter { it.isExternalSource() }
        .mapTo(linkedSetOf()) { normalizeSourceProvider(it.providerName) }
        .intersect(SUPPLEMENTAL_PROVIDER_IDS)
}

fun normalizeSourceProvider(providerName: String): String {
    return providerName.trim().lowercase().ifBlank { AGE_PROVIDER_ID }
}
