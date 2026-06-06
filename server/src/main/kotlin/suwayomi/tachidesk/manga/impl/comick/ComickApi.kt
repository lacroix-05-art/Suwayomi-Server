package suwayomi.tachidesk.manga.impl.comick

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

import eu.kanade.tachiyomi.network.NetworkHelper
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import suwayomi.tachidesk.manga.impl.util.network.await
import uy.kohesive.injekt.injectLazy

private val logger = KotlinLogging.logger {}

data class ComickSearchResult(
    val title: String,
    val lastChapter: Double,
    val slug: String,
)

/**
 * HTTP client for the Comick.dev API.
 *
 * Provides a single [search] method that queries the public API endpoint.
 */
class ComickApi {
    private val network: NetworkHelper by injectLazy()
    private val json: Json by injectLazy()

    /**
     * Searches Comick.dev for manga matching [title].
     *
     * @param title The search query, manually URL-encoded before being sent to the API.
     * @param limit Maximum number of results to return (default 8).
     * @return A list of [ComickSearchResult] – empty on network errors or parse failures.
     */
    suspend fun search(
        title: String,
        limit: Int = 8,
    ): List<ComickSearchResult> {
        val encodedTitle = URLEncoder.encode(title, "UTF-8")
        val url = "https://api.comick.dev/v1.0/search?q=$encodedTitle&limit=$limit"
        val request = Request.Builder().url(url).get().build()

        return try {
            val responseBody = network.client.newCall(request).await().use { response ->
                if (!response.isSuccessful) {
                    logger.warn { "Comick API search returned ${response.code} for query '$title'" }
                    return emptyList()
                }
                response.body.string()
            }

            parseSearchResponse(responseBody)
        } catch (e: Exception) {
            logger.warn(e) { "Comick API search failed for query '$title'" }
            emptyList()
        }
    }

    private fun parseSearchResponse(jsonString: String): List<ComickSearchResult> {
        return try {
            val root: JsonElement = json.parseToJsonElement(jsonString)

            val items: JsonArray = when (root) {
                is JsonObject -> root["results"]?.jsonArray ?: return emptyList()
                is JsonArray -> root
                else -> return emptyList()
            }

            items.mapNotNull { element ->
                val obj = element.jsonObject
                val slug = obj["slug"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val titleText = obj["title"]?.jsonPrimitive?.content ?: return@mapNotNull null

                // last_chapter can be a number or null
                val lastChapter = obj["last_chapter"]?.jsonPrimitive?.doubleOrNull ?: 0.0

                ComickSearchResult(
                    title = titleText,
                    lastChapter = lastChapter,
                    slug = slug,
                )
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse Comick API search response" }
            emptyList()
        }
    }
}
