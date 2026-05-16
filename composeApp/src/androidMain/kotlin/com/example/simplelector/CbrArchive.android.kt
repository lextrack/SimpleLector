package com.example.simplelector

import android.content.ContentResolver
import android.net.Uri
import com.github.junrar.Archive
import java.io.File
import java.security.MessageDigest

private const val NativeImageLogTag = "SimpleLectorNative"

internal object AndroidCbrArchiveCache {
    private const val MAX_ENTRIES = 4
    private val cachedFiles = LinkedHashMap<String, File>(MAX_ENTRIES, 0.75f, true)

    @Synchronized
    fun getOrCreate(
        contentResolver: ContentResolver,
        cacheDir: File,
        uri: Uri,
        sourceVersionKey: String,
    ): File? {
        val sourceId = uri.toString()
        val cacheKey = "$sourceId|$sourceVersionKey"
        cachedFiles[cacheKey]?.takeIf(File::exists)?.let {
            debugLog(
                NativeImageLogTag,
                "CBR archive cache HIT file=${it.name} size=${cachedFiles.size}",
            )
            return it
        }
        debugLog(
            NativeImageLogTag,
            "CBR archive cache MISS source=${uri.lastPathSegment ?: sourceId} size=${cachedFiles.size}",
        )

        val archiveCacheDir = File(cacheDir, "cbr-archive-cache").apply { mkdirs() }
        val tempFile = File(archiveCacheDir, "${sha256Hex(cacheKey)}.cbr")
        return runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().buffered().use { output ->
                    input.copyTo(output)
                }
            } ?: return null
            cachedFiles[cacheKey] = tempFile
            debugLog(
                NativeImageLogTag,
                "CBR archive cache STORE file=${tempFile.name} size=${cachedFiles.size}",
            )
            while (cachedFiles.size > MAX_ENTRIES) {
                val eldest = cachedFiles.entries.firstOrNull() ?: break
                cachedFiles.remove(eldest.key)?.takeIf(File::exists)?.let { removed ->
                    removed.delete()
                    debugLog(
                        NativeImageLogTag,
                        "CBR archive cache EVICT file=${removed.name} size=${cachedFiles.size}",
                    )
                }
            }
            tempFile
        }.getOrElse {
            tempFile.delete()
            debugLog(
                NativeImageLogTag,
                "CBR archive cache ERROR source=${uri.lastPathSegment ?: sourceId} message=${it.message ?: "unknown"}",
            )
            null
        }
    }
}

internal inline fun <T> withTempAndroidCbrArchive(
    contentResolver: ContentResolver,
    cacheDir: File,
    uri: Uri,
    sourceVersionKey: String,
    crossinline block: (Archive) -> T,
): T? {
    val cachedFile = AndroidCbrArchiveCache.getOrCreate(
        contentResolver = contentResolver,
        cacheDir = cacheDir,
        uri = uri,
        sourceVersionKey = sourceVersionKey,
    ) ?: return null
    debugLog(
        NativeImageLogTag,
        "CBR archive OPEN file=${cachedFile.name}",
    )
    return Archive(cachedFile).use { archive ->
        block(archive)
    }
}

internal fun copyAndroidUriToTempFile(
    contentResolver: ContentResolver,
    cacheDir: File,
    uri: Uri,
    prefix: String,
    suffix: String,
): File? {
    val tempFile = File.createTempFile(prefix, suffix, cacheDir)
    return runCatching {
        contentResolver.openInputStream(uri)?.use { input ->
            tempFile.outputStream().buffered().use { output ->
                input.copyTo(output)
            }
        } ?: return null
        tempFile
    }.getOrElse {
        tempFile.delete()
        null
    }
}

private fun sha256Hex(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { byte -> "%02x".format(byte) }
