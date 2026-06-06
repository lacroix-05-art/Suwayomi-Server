package suwayomi.tachidesk.manga.impl.comick

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import suwayomi.tachidesk.manga.model.table.MangaTable

private val logger = KotlinLogging.logger {}

/**
 * Interval between automatic background refreshes, in hours.
 * All manga in the library are refreshed this often.
 */
const val COMICK_AUTO_REFRESH_INTERVAL_HOURS = 24L

/**
 * Orchestrates Comick tracker operations.
 *
 * Combines [ComickDataStore] persistence and [ComickApi] search to
 * provide the core [refresh] and [getTrack] methods.
 *
 * Rate limiting is enforced by tracking the last API call timestamp:
 * at least 1500 ms must elapse between consecutive actual API calls.
 *
 * An automatic background refresh runs every [COMICK_AUTO_REFRESH_INTERVAL_HOURS]
 * hours for all manga in the library, started lazily on first [refresh] call.
 */
object ComickTracker {
    private val api = ComickApi()
    private val rateLimitLock = Mutex()
    private var lastApiCallTimestamp: Long = 0

    private val autoRefreshJob: Job by lazy {
        GlobalScope.launch {
            while (isActive) {
                delay(COMICK_AUTO_REFRESH_INTERVAL_HOURS * 60 * 60 * 1000L)
                try {
                    runAutoRefresh()
                } catch (e: Exception) {
                    logger.error(e) { "Comick auto-refresh cycle failed, will retry next interval" }
                }
            }
        }
    }

    private suspend fun runAutoRefresh() {
        val libraryMangaIds = transaction {
            MangaTable.select(MangaTable.id)
                .where { MangaTable.inLibrary eq true }
                .map { it[MangaTable.id].value }
        }

        for (mangaId in libraryMangaIds) {
            try {
                val mangaTitle = transaction {
                    MangaTable.select(MangaTable.title)
                        .where { MangaTable.id eq mangaId }
                        .singleOrNull()
                        ?.get(MangaTable.title)
                } ?: continue

                refresh(mangaId, mangaTitle)
            } catch (e: Exception) {
                // Silently skip per-item errors
            }
        }
    }

    /**
     * Ensures the background auto-refresh job is started.
     * Safe to call multiple times — the job is created only once via lazy.
     */
    fun ensureAutoRefreshRunning() {
        autoRefreshJob
    }

    /**
     * Reads the cached track info for [mangaId] without making an API call.
     */
    fun getTrack(mangaId: Int): ComickCacheEntry? = ComickDataStore.getCache(mangaId)

    /**
     * Refreshes the track info for [mangaId] by searching Comick.dev.
     *
     * Resolution order for the search title:
     * 1. [searchQuery] (explicit parameter)
     * 2. [ComickDataStore.getOverride] (user-set custom query)
     * 3. [mangaTitle] (the actual manga title from the DB)
     *
     * Results are filtered: we keep items whose title contains the search title
     * (case-insensitive) or vice versa. If no relevant match is found, the
     * fallback is to pick the result with the highest [lastChapter].
     *
     * The result is persisted to [ComickDataStore.setCache] and returned.
     *
     * This method also kicks off the background auto-refresh loop on first call.
     *
     * @param mangaId The manga's internal ID.
     * @param mangaTitle The manga's title (used as fallback search query).
     * @param searchQuery An optional explicit override for the search.
     * @return The cached entry after refresh, or `null` if the search returned nothing.
     */
    suspend fun refresh(
        mangaId: Int,
        mangaTitle: String,
        searchQuery: String? = null,
    ): ComickCacheEntry? = rateLimitLock.withLock {
        ensureAutoRefreshRunning()

        // Determine search title
        val effectiveQuery = searchQuery
            ?: ComickDataStore.getOverride(mangaId)
            ?: mangaTitle

        if (effectiveQuery.isBlank()) {
            logger.info { "Skipping Comick refresh for mangaId=$mangaId because search query is blank" }
            return@withLock null
        }

        // Enforce rate limit: at least 1500 ms between API calls
        val now = System.currentTimeMillis()
        val elapsed = now - lastApiCallTimestamp
        if (elapsed < 1500L) {
            val sleepMs = 1500L - elapsed
            delay(sleepMs)
        }
        lastApiCallTimestamp = System.currentTimeMillis()

        val results = api.search(effectiveQuery)

        if (results.isEmpty()) {
            logger.info { "Comick search returned no results for mangaId=$mangaId query='$effectiveQuery'" }
            return@withLock null
        }

        // Try to find a relevant match
        val queryLower = effectiveQuery.lowercase()
        val relevant = results.filter { result ->
            val titleLower = result.title.lowercase()
            titleLower.contains(queryLower) || queryLower.contains(titleLower)
        }

        val bestResult = if (relevant.isNotEmpty()) {
            relevant.maxByOrNull { it.lastChapter }
        } else {
            // Fallback: pick the result with the highest lastChapter
            logger.info { "No relevant Comick match for mangaId=$mangaId query='$effectiveQuery', using fallback" }
            results.maxByOrNull { it.lastChapter }
        } ?: return@withLock null

        val entry = ComickCacheEntry(
            lastChapter = bestResult.lastChapter,
            slug = bestResult.slug,
            fetchedAt = System.currentTimeMillis(),
        )

        ComickDataStore.setCache(mangaId, entry)
        entry
    }
}
