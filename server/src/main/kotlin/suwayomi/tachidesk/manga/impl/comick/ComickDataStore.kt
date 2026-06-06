package suwayomi.tachidesk.manga.impl.comick

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import suwayomi.tachidesk.server.ApplicationDirs
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.io.path.createDirectories

private val logger = KotlinLogging.logger {}

data class ComickCacheEntry(
    val lastChapter: Double,
    val slug: String,
    val fetchedAt: Long,
)

/**
 * Serializable wrapper for the cache JSON file contents.
 */
@Serializable
private data class CacheFile(
    val entries: Map<String, CacheEntryJson> = emptyMap(),
)

/**
 * Serializable wrapper for the overrides JSON file contents.
 */
@Serializable
private data class OverridesFile(
    val entries: Map<String, String> = emptyMap(),
)

@Serializable
private data class CacheEntryJson(
    val lastChapter: Double,
    val slug: String,
    val fetchedAt: Long,
) {
    fun toEntry() = ComickCacheEntry(
        lastChapter = lastChapter,
        slug = slug,
        fetchedAt = fetchedAt,
    )
}

private fun ComickCacheEntry.toJson() = CacheEntryJson(
    lastChapter = lastChapter,
    slug = slug,
    fetchedAt = fetchedAt,
)

/**
 * Persistent JSON-file-based storage for Comick tracker data.
 *
 * Manages two files inside `<dataRoot>/forkdata/`:
 * - `comick_cache.json`: cached search results (mangaId -> ComickCacheEntry)
 * - `comick_overrides.json`: custom search query overrides (mangaId -> query string)
 *
 * All reads and writes are thread-safe via [ReentrantReadWriteLock].
 * Writes are atomic: written to a temp file first, then renamed over the target.
 * On parse failure the corrupted file is backed up and a fresh empty state is used.
 */
object ComickDataStore {
    private val applicationDirs: ApplicationDirs by injectLazy()
    private val json: Json by injectLazy()

    private val lock = ReentrantReadWriteLock()

    private val forkDataDir: File
        get() {
            val dir = File(applicationDirs.dataRoot, "forkdata")
            dir.toPath().createDirectories()
            return dir
        }

    private val cacheFile: File
        get() = File(forkDataDir, "comick_cache.json")

    private val overridesFile: File
        get() = File(forkDataDir, "comick_overrides.json")

    // -----------------------------------------------------------------------
    // Cached search results
    // -----------------------------------------------------------------------

    fun getCache(mangaId: Int): ComickCacheEntry? = lock.read {
        val store = readCacheFile()
        store[mangaId.toString()]?.toEntry()
    }

    fun setCache(mangaId: Int, entry: ComickCacheEntry) = lock.write {
        val store = readCacheFile().toMutableMap()
        store[mangaId.toString()] = entry.toJson()
        writeCacheFileAtomic(store)
    }

    fun clearCache(mangaId: Int): Boolean = lock.write {
        val store = readCacheFile().toMutableMap()
        val removed = store.remove(mangaId.toString()) != null
        if (removed) {
            writeCacheFileAtomic(store)
        }
        removed
    }

    fun getAllCaches(): Map<Int, ComickCacheEntry> = lock.read {
        readCacheFile()
            .mapNotNull { (k, v) ->
                k.toIntOrNull()?.let { it to v.toEntry() }
            }
            .toMap()
    }

    // -----------------------------------------------------------------------
    // Search query overrides
    // -----------------------------------------------------------------------

    fun getOverride(mangaId: Int): String? = lock.read {
        readOverridesFile()[mangaId.toString()]
    }

    fun setOverride(mangaId: Int, query: String) = lock.write {
        val store = readOverridesFile().toMutableMap()
        store[mangaId.toString()] = query
        writeOverridesFileAtomic(store)
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    private fun readCacheFile(): Map<String, CacheEntryJson> {
        if (!cacheFile.exists()) return emptyMap()
        return try {
            val text = cacheFile.readText().trim()
            if (text.isEmpty()) return emptyMap()
            json.decodeFromString<CacheFile>(text).entries
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse comick_cache.json, backing up and resetting" }
            backupCorruptedFile(cacheFile)
            emptyMap()
        }
    }

    private fun writeCacheFileAtomic(store: Map<String, CacheEntryJson>) {
        val fileContent = CacheFile(entries = store)
        val tempFile = File(cacheFile.parentFile, "${cacheFile.name}.tmp")
        try {
            tempFile.writeText(json.encodeToString(fileContent))
            Files.move(tempFile.toPath(), cacheFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to write comick_cache.json atomically; falling back to direct write" }
            cacheFile.writeText(json.encodeToString(fileContent))
            tempFile.delete()
        }
    }

    private fun readOverridesFile(): Map<String, String> {
        if (!overridesFile.exists()) return emptyMap()
        return try {
            val text = overridesFile.readText().trim()
            if (text.isEmpty()) return emptyMap()
            json.decodeFromString<OverridesFile>(text).entries
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse comick_overrides.json, backing up and resetting" }
            backupCorruptedFile(overridesFile)
            emptyMap()
        }
    }

    private fun writeOverridesFileAtomic(store: Map<String, String>) {
        val fileContent = OverridesFile(entries = store)
        val tempFile = File(overridesFile.parentFile, "${overridesFile.name}.tmp")
        try {
            tempFile.writeText(json.encodeToString(fileContent))
            Files.move(tempFile.toPath(), overridesFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to write comick_overrides.json atomically; falling back to direct write" }
            overridesFile.writeText(json.encodeToString(fileContent))
            tempFile.delete()
        }
    }

    private fun backupCorruptedFile(file: File) {
        try {
            val backup = File(file.parentFile, "${file.name}.bak.${System.currentTimeMillis()}")
            file.renameTo(backup)
            logger.info { "Backed up corrupted file to ${backup.absolutePath}" }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to backup corrupted file ${file.absolutePath}" }
        }
    }
}
