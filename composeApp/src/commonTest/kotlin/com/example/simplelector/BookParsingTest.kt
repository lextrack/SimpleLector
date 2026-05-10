package com.example.simplelector

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class BookParsingTest {
    @Test
    fun htmlToReaderBlocks_collapsesSourceWrappedParagraphLines() {
        val blocks = htmlToReaderBlocks(
            html = "<p>tan escasas que se agotaban\nrapidamente y uno se quedaba</p>",
            resolveImage = { null },
        )

        assertEquals(1, blocks.size)
        assertEquals(ReaderContentKind.Paragraph, blocks.single().kind)
        assertEquals(
            "tan escasas que se agotaban rapidamente y uno se quedaba",
            blocks.single().text,
        )
    }

    @Test
    fun htmlToReaderBlocks_preservesExplicitBrInsideParagraph() {
        val blocks = htmlToReaderBlocks(
            html = "<p>uno<br>dos</p>",
            resolveImage = { null },
        )

        assertEquals(1, blocks.size)
        assertEquals("uno\ndos", blocks.single().text)
    }

    @Test
    fun buildReaderDocumentFromSectionSources_mergesContinuationParagraphs() {
        val document = buildReaderDocumentFromSectionSources(
            sections = listOf(
                ReaderSectionSource(
                    title = null,
                    blocks = listOf(
                        ReaderContentBlock(ReaderContentKind.Paragraph, text = "tan escasas que se agotaban"),
                        ReaderContentBlock(ReaderContentKind.Paragraph, text = "rapidamente y uno se quedaba sin saber"),
                    ),
                ),
            ),
            pageWeightLimit = 1_900,
        )

        val paragraphs = document.pages.single().blocks.filter { it.kind == ReaderContentKind.Paragraph }
        assertEquals(1, paragraphs.size)
        assertEquals(
            "tan escasas que se agotaban rapidamente y uno se quedaba sin saber",
            paragraphs.single().text,
        )
    }

    @Test
    fun buildReaderDocumentFromSectionSources_keepsDistinctParagraphs() {
        val document = buildReaderDocumentFromSectionSources(
            sections = listOf(
                ReaderSectionSource(
                    title = null,
                    blocks = listOf(
                        ReaderContentBlock(ReaderContentKind.Paragraph, text = "Se cerraron las puertas."),
                        ReaderContentBlock(ReaderContentKind.Paragraph, text = "Rosa miro hacia afuera."),
                    ),
                ),
            ),
            pageWeightLimit = 1_900,
        )

        val paragraphs = document.pages.single().blocks.filter { it.kind == ReaderContentKind.Paragraph }
        assertEquals(2, paragraphs.size)
        assertFalse(paragraphs[0].text == paragraphs[1].text)
    }

    @Test
    fun buildReaderDocumentFromSectionSources_keepsShortTitledSectionsOnSamePage() {
        val document = buildReaderDocumentFromSectionSources(
            sections = listOf(
                ReaderSectionSource(
                    title = "Capitulo 1",
                    blocks = listOf(
                        ReaderContentBlock(ReaderContentKind.Heading, text = "Capitulo 1"),
                        ReaderContentBlock(
                            ReaderContentKind.Paragraph,
                            text = "Un bloque corto de texto que no deberia forzar un salto de pagina por si solo.",
                        ),
                    ),
                ),
                ReaderSectionSource(
                    title = "Capitulo 2",
                    blocks = listOf(
                        ReaderContentBlock(ReaderContentKind.Heading, text = "Capitulo 2"),
                        ReaderContentBlock(
                            ReaderContentKind.Paragraph,
                            text = "Otro bloque corto para comprobar que una seccion breve puede seguir en la misma pagina.",
                        ),
                    ),
                ),
            ),
            pageWeightLimit = 1_900,
        )

        assertEquals(1, document.totalPages)
        assertEquals(4, document.pages.single().blocks.size)
    }

    @Test
    fun buildReaderDocumentFromSectionSources_canStartNewPageEarlierForEpubLikeSections() {
        val document = buildReaderDocumentFromSectionSources(
            sections = listOf(
                ReaderSectionSource(
                    title = "Capitulo 1",
                    blocks = listOf(
                        ReaderContentBlock(ReaderContentKind.Heading, text = "Capitulo 1"),
                        ReaderContentBlock(
                            ReaderContentKind.Paragraph,
                            text = "Un bloque inicial con suficiente contenido para justificar un corte mas conservador antes del siguiente capitulo.",
                        ),
                    ),
                ),
                ReaderSectionSource(
                    title = "Capitulo 2",
                    blocks = listOf(
                        ReaderContentBlock(ReaderContentKind.Heading, text = "Capitulo 2"),
                        ReaderContentBlock(
                            ReaderContentKind.Paragraph,
                            text = "Otro bloque breve que, en modo EPUB, debe comenzar en una nueva pagina.",
                        ),
                    ),
                ),
            ),
            pageWeightLimit = 1_900,
            sectionBreakThresholdFraction = 0.2f,
        )

        assertEquals(2, document.totalPages)
    }

    @Test
    fun buildReaderDocumentFromSectionSources_doesNotMergeDistinctEpubParagraphsWhenDisabled() {
        val document = buildReaderDocumentFromSectionSources(
            sections = listOf(
                ReaderSectionSource(
                    path = "chapter-1.xhtml",
                    title = "Capitulo 1",
                    blocks = listOf(
                        ReaderContentBlock(ReaderContentKind.Paragraph, text = "primera idea sin punto final"),
                        ReaderContentBlock(ReaderContentKind.Paragraph, text = "segunda idea que en EPUB debe seguir separada"),
                    ),
                ),
            ),
            pageWeightLimit = 1_900,
            mergeContinuationParagraphs = false,
        )

        val paragraphs = document.pages.single().blocks.filter { it.kind == ReaderContentKind.Paragraph }
        assertEquals(2, paragraphs.size)
    }

    @Test
    fun buildReaderDocumentFromSectionSources_canForcePageBreakBetweenEpubSections() {
        val document = buildReaderDocumentFromSectionSources(
            sections = listOf(
                ReaderSectionSource(
                    path = "chapter-1.xhtml",
                    title = "Capitulo 1",
                    blocks = listOf(
                        ReaderContentBlock(ReaderContentKind.Paragraph, text = "Texto breve del primer archivo."),
                    ),
                ),
                ReaderSectionSource(
                    path = "chapter-2.xhtml",
                    title = "Capitulo 2",
                    blocks = listOf(
                        ReaderContentBlock(ReaderContentKind.Paragraph, text = "Texto breve del segundo archivo."),
                    ),
                ),
            ),
            pageWeightLimit = 1_900,
            forcePageBreakBetweenSections = true,
        )

        assertEquals(2, document.totalPages)
    }
}
