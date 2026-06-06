@file:Suppress("RedundantNullableReturnType", "unused")

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package suwayomi.tachidesk.graphql.mutations

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import suwayomi.tachidesk.graphql.directives.RequireAuth
import suwayomi.tachidesk.graphql.types.ComickTrackType
import suwayomi.tachidesk.manga.impl.comick.ComickDataStore
import suwayomi.tachidesk.manga.impl.comick.ComickTracker
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.server.JavalinSetup.future
import java.util.concurrent.CompletableFuture

/**
 * GraphQL mutations for Comick.dev tracker operations.
 */
class ComickMutation {
    /**
     * Refreshes the Comick track data for the given manga by searching the
     * Comick.dev API. Returns the updated [ComickTrackType] or `null` if
     * the search returned no results.
     *
     * The manga title is read from the database to use as the search query
     * (unless [searchQuery] overrides it).
     */
    @RequireAuth
    fun refreshComickTrack(
        mangaId: Int,
        searchQuery: String? = null,
    ): CompletableFuture<ComickTrackType?> = future {
        // Read the manga title from the DB
        val mangaTitle = transaction {
            MangaTable
                .selectAll()
                .where { MangaTable.id eq mangaId }
                .firstOrNull()
                ?.get(MangaTable.title)
        } ?: return@future null

        val entry = ComickTracker.refresh(mangaId, mangaTitle, searchQuery) ?: return@future null

        ComickTrackType(
            mangaId = mangaId,
            chapterCount = entry.lastChapter,
            slug = entry.slug,
            fetchedAt = entry.fetchedAt,
        )
    }

    /**
     * Sets a custom search query override for the given manga.
     * When set, this query will be used instead of the manga title when
     * refreshing the Comick track data.
     */
    @RequireAuth
    fun setComickSearchOverride(
        mangaId: Int,
        query: String,
    ): Boolean {
        ComickDataStore.setOverride(mangaId, query)
        return true
    }

    /**
     * Clears the cached Comick track data for the given manga.
     * Returns `true` if data was actually removed, `false` if nothing was cached.
     */
    @RequireAuth
    fun clearComickCache(mangaId: Int): Boolean {
        return ComickDataStore.clearCache(mangaId)
    }
}
