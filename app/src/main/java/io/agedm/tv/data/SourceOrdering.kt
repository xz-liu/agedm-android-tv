package io.agedm.tv.data

private const val AGE_PROVIDER_ID = "age"
val SUPPLEMENTAL_PROVIDER_IDS: Set<String> = linkedSetOf("aafun", "dm84")

fun List<EpisodeSource>.mergeDistinctSources(extraSources: List<EpisodeSource>): List<EpisodeSource> {
    if (extraSources.isEmpty()) return this
    // Fresh episodes must replace stale data even when the source key is unchanged.
    val byKey = associateByTo(linkedMapOf()) { it.key }
    extraSources.forEach { byKey[it.key] = it }
    return byKey.values.toList()
}

fun List<EpisodeSource>.replaceSourcesForProvider(
    providerId: String,
    replacement: List<EpisodeSource>,
): List<EpisodeSource> {
    val normalizedProvider = normalizeSourceProvider(providerId)
    return filterNot { normalizeSourceProvider(it.providerName) == normalizedProvider } + replacement
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
