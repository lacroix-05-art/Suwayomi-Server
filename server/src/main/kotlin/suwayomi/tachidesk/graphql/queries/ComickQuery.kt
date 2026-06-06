/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package suwayomi.tachidesk.graphql.queries

import suwayomi.tachidesk.graphql.directives.RequireAuth
import suwayomi.tachidesk.graphql.types.ComickTrackType
import suwayomi.tachidesk.manga.impl.comick.ComickDataStore

/**
 * GraphQL queries for Comick.dev tracker data.
 */
class ComickQuery {
    /**
     * Returns the cached Comick track data for a single manga, or `null` if not tracked yet.
     */
    @RequireAuth
    fun comickTrack(mangaId: Int): ComickTrackType? {
        val entry = ComickDataStore.getCache(mangaId) ?: return null
        return ComickTrackType(
            mangaId = mangaId,
            chapterCount = entry.lastChapter,
            slug = entry.slug,
            fetchedAt = entry.fetchedAt,
        )
    }

    /**
     * Returns the cached Comick track data for multiple manga IDs.
     *
     * The result list has the same order and size as [mangaIds]; entries
     * for untracked manga are `null`.
     */
    @RequireAuth
    fun comickTracks(mangaIds: List<Int>): List<ComickTrackType?> {
        return mangaIds.map { mangaId ->
            val entry = ComickDataStore.getCache(mangaId) ?: return@map null
            ComickTrackType(
                mangaId = mangaId,
                chapterCount = entry.lastChapter,
                slug = entry.slug,
                fetchedAt = entry.fetchedAt,
            )
        }
    }
}
