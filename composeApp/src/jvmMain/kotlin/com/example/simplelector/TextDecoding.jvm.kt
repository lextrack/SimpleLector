package com.example.simplelector

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

actual fun decodeBookText(bytes: ByteArray): String =
    decodeTextBytes(bytes)

private fun decodeTextBytes(bytes: ByteArray): String {
    if (bytes.isEmpty()) return ""

    detectBomCharset(bytes)?.let { charset ->
        val offset = bomSize(bytes)
        return charset.decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset)).toString()
    }

    val charsetOrder = listOf(
        StandardCharsets.UTF_8,
        StandardCharsets.UTF_16LE,
        StandardCharsets.UTF_16BE,
        Charset.forName("windows-1252"),
        StandardCharsets.ISO_8859_1,
    )

    charsetOrder.forEach { charset ->
        decodeStrict(bytes, charset)?.let { return it }
    }

    return String(bytes, StandardCharsets.UTF_8)
}

private fun decodeStrict(bytes: ByteArray, charset: Charset): String? =
    try {
        charset
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

private fun detectBomCharset(bytes: ByteArray): Charset? = when {
    bytes.size >= 3 &&
        bytes[0] == 0xEF.toByte() &&
        bytes[1] == 0xBB.toByte() &&
        bytes[2] == 0xBF.toByte() -> StandardCharsets.UTF_8
    bytes.size >= 2 &&
        bytes[0] == 0xFF.toByte() &&
        bytes[1] == 0xFE.toByte() -> StandardCharsets.UTF_16LE
    bytes.size >= 2 &&
        bytes[0] == 0xFE.toByte() &&
        bytes[1] == 0xFF.toByte() -> StandardCharsets.UTF_16BE
    else -> null
}

private fun bomSize(bytes: ByteArray): Int = when {
    bytes.size >= 3 &&
        bytes[0] == 0xEF.toByte() &&
        bytes[1] == 0xBB.toByte() &&
        bytes[2] == 0xBF.toByte() -> 3
    bytes.size >= 2 &&
        ((bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) ||
            (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte())) -> 2
    else -> 0
}
