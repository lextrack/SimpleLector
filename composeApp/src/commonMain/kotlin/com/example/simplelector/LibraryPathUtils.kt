package com.example.simplelector

fun Book.parentFolderPath(): String? = path.parentPath()

fun String.parentPath(): String? {
    val normalized = trimEnd('/', '\\')
    val slash = normalized.lastIndexOf('/')
    val backslash = normalized.lastIndexOf('\\')
    val index = maxOf(slash, backslash)
    return if (index <= 0) null else normalized.substring(0, index)
}

fun String.relativeFolderUnder(parentPath: String?): String? {
    val bookFolder = parentPath()
    if (bookFolder == null) return null
    if (parentPath == null) {
        return rootFolderPath(bookFolder)
    }
    if (bookFolder == parentPath) return null
    val prefix = parentPath.trimEnd('/', '\\') + separatorAfter(parentPath)
    if (!bookFolder.startsWith(prefix)) return null
    val remaining = bookFolder.removePrefix(prefix)
    val nextSegment = remaining.substringBefore('/').substringBefore('\\')
    return if (nextSegment.isBlank()) null else prefix + nextSegment
}

fun String.isInsideFolder(folderPath: String): Boolean {
    val bookFolder = parentPath() ?: return false
    return bookFolder.isSameOrDescendantFolder(folderPath)
}

fun String.isSameOrDescendantFolder(folderPath: String): Boolean {
    val normalizedCandidate = normalizeFolderNavigationPath()
    val normalizedFolder = folderPath.normalizeFolderNavigationPath()
    return normalizedCandidate == normalizedFolder ||
        normalizedCandidate.startsWith("$normalizedFolder/")
}

fun String.friendlyStoragePath(): String =
    decodeUriEscapes()
        .removeAndroidTreePrefix()
        .removeAndroidStoragePrefix()
        .replace("\\", "/")
        .trim('/')
        .ifBlank { this }

fun String.friendlyFolderName(): String =
    friendlyStoragePath()
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .ifBlank { friendlyStoragePath() }

private fun rootFolderPath(path: String): String {
    val slash = path.indexOf('/')
    val backslash = path.indexOf('\\')
    val indexes = listOf(slash, backslash).filter { it >= 0 }
    val index = indexes.minOrNull() ?: return path
    return if (index == 0) {
        val secondSlash = path.indexOf('/', startIndex = 1)
        val secondBackslash = path.indexOf('\\', startIndex = 1)
        val second = listOf(secondSlash, secondBackslash).filter { it > 0 }.minOrNull()
        if (second == null) path else path.substring(0, second)
    } else {
        path.substring(0, index)
    }
}

private fun separatorAfter(path: String): String =
    if (path.contains('\\') && !path.contains('/')) "\\" else "/"

private fun String.normalizeFolderNavigationPath(): String =
    replace('\\', '/')
        .replace(Regex("/+"), "/")
        .trimEnd('/')

private fun String.removeAndroidStoragePrefix(): String {
    if (startsWith("primary:")) return removePrefix("primary:")
    val colonIndex = indexOf(':')
    val slashIndex = listOf(indexOf('/'), indexOf('\\')).filter { it >= 0 }.minOrNull() ?: Int.MAX_VALUE
    val looksLikeAndroidVolume = colonIndex in 1 until slashIndex && !(colonIndex == 1 && first().isLetter())
    return if (looksLikeAndroidVolume) substring(colonIndex + 1) else this
}

private fun String.removeAndroidTreePrefix(): String {
    val marker = "/tree/"
    val markerIndex = indexOf(marker)
    return if (markerIndex >= 0) substring(markerIndex + marker.length) else this
}

private fun String.decodeUriEscapes(): String {
    val builder = StringBuilder(length)
    var index = 0
    while (index < length) {
        val char = this[index]
        if (char == '%' && index + 2 < length) {
            val value = substring(index + 1, index + 3).toIntOrNull(16)
            if (value != null) {
                builder.append(value.toChar())
                index += 3
                continue
            }
        }
        builder.append(char)
        index++
    }
    return builder.toString()
}
