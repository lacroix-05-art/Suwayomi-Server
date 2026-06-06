/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package suwayomi.tachidesk.graphql.types

/**
 * GraphQL type representing a Comick.dev tracking entry for a single manga.
 */
class ComickTrackType(
    val mangaId: Int,
    val chapterCount: Double?,
    val slug: String?,
    val fetchedAt: Long?,
) {
    /**
     * Returns the Comick.dev URL for this manga, or `null` if [slug] is not set.
     */
    fun url(): String? = slug?.let { "https://comick.dev/comic/$it" }
}
