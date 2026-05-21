package com.example.simplelector

import kotlin.test.Test
import kotlin.test.assertContentEquals

class CbzParsingTest {
    @Test
    fun extractCbzCoverBytes_respectsComicInfoWhenTypeComesBeforeImage() {
        val first = byteArrayOf(1, 2, 3)
        val second = byteArrayOf(4, 5, 6)
        val entries = linkedMapOf(
            "001.jpg" to first,
            "002.jpg" to second,
            "ComicInfo.xml" to """
                <ComicInfo>
                  <Pages>
                    <Page Type="FrontCover" Image="1" />
                  </Pages>
                </ComicInfo>
            """.trimIndent().encodeToByteArray(),
        )

        val cover = extractCbzCoverBytes(entries)

        assertContentEquals(second, cover)
    }
}
