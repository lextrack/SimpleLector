package com.example.simplelector

import kotlin.test.Test
import kotlin.test.assertEquals

class ReadingProgressStoreTest {
    @Test
    fun encodeAndDecodeSavedUiPreferences_roundTripsCurrentFormatWithoutSection() {
        val saved = SavedUiPreferences(
            libraryViewMode = LibraryViewMode.Folders,
            libraryPresentationMode = LibraryPresentationMode.Carousel,
            currentLibraryFolderPath = "/library/series",
            readerTheme = ReaderTheme.Sepia,
            keepScreenOn = true,
            lockRotationInReader = true,
            showProgress = false,
            showPageButtons = true,
            fontSize = 22,
            lineHeightExtra = 14,
            readerSidePadding = 18,
        )

        val decoded = decodeSavedUiPreferences(encodeSavedUiPreferences(saved))

        assertEquals(saved, decoded)
    }

    @Test
    fun decodeSavedUiPreferences_supportsLegacyPayloadWithPersistedSection() {
        val legacyRaw = listOf(
            AppSection.Reader.name,
            LibraryViewMode.Folders.name,
            LibraryPresentationMode.Carousel.name,
            escapeProgressField("/legacy/path"),
            ReaderTheme.Dark.name,
            "1",
            "1",
            "0",
            "1",
            "24",
            "15",
            "20",
        ).joinToString("\t")

        val decoded = decodeSavedUiPreferences(legacyRaw)

        assertEquals(
            SavedUiPreferences(
                libraryViewMode = LibraryViewMode.Folders,
                libraryPresentationMode = LibraryPresentationMode.Carousel,
                currentLibraryFolderPath = "/legacy/path",
                readerTheme = ReaderTheme.Dark,
                keepScreenOn = true,
                lockRotationInReader = true,
                showProgress = false,
                showPageButtons = true,
                fontSize = 24,
                lineHeightExtra = 15,
                readerSidePadding = 20,
            ),
            decoded,
        )
    }
}
