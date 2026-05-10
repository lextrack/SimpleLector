package com.example.simplelector

import android.content.ContentResolver
import android.net.Uri
import com.github.junrar.Archive
import java.io.File

internal inline fun <T> withTempAndroidCbrArchive(
    contentResolver: ContentResolver,
    cacheDir: File,
    uri: Uri,
    crossinline block: (Archive) -> T,
): T? {
    val tempFile = copyAndroidUriToTempFile(
        contentResolver = contentResolver,
        cacheDir = cacheDir,
        uri = uri,
        prefix = "simplelector-cbr-",
        suffix = ".cbr",
    ) ?: return null
    return try {
        Archive(tempFile).use { archive ->
            block(archive)
        }
    } finally {
        tempFile.delete()
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
