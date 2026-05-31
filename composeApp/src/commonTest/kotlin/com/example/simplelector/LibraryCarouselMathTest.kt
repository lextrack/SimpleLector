package com.example.simplelector

import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryCarouselMathTest {
    @Test
    fun centeredCarouselScrollFor_returnsExactStrideWhenCardsCanBeCentered() {
        val cardWidthPx = 184f
        val itemSpacingPx = 14f
        val viewportWidthPx = 360f
        val sidePaddingPx = (viewportWidthPx - cardWidthPx) / 2f
        val viewportCenterPx = viewportWidthPx / 2f
        val maxScroll = 10_000

        assertEquals(
            0,
            centeredCarouselScrollFor(
                index = 0,
                itemCount = 6,
                cardWidthPx = cardWidthPx,
                itemSpacingPx = itemSpacingPx,
                sidePaddingPx = sidePaddingPx,
                viewportCenterPx = viewportCenterPx,
                maxScroll = maxScroll,
            ),
        )
        assertEquals(
            396,
            centeredCarouselScrollFor(
                index = 2,
                itemCount = 6,
                cardWidthPx = cardWidthPx,
                itemSpacingPx = itemSpacingPx,
                sidePaddingPx = sidePaddingPx,
                viewportCenterPx = viewportCenterPx,
                maxScroll = maxScroll,
            ),
        )
    }

    @Test
    fun centeredCarouselScrollFor_roundsToNearestPixelInsteadOfTruncating() {
        assertEquals(
            101,
            centeredCarouselScrollFor(
                index = 1,
                itemCount = 4,
                cardWidthPx = 100.5f,
                itemSpacingPx = 0f,
                sidePaddingPx = 0f,
                viewportCenterPx = 50f,
                maxScroll = 1_000,
            ),
        )
    }

    @Test
    fun nearestCarouselIndexForScroll_matchesClosestVisualCenter() {
        val cardWidthPx = 184f
        val itemSpacingPx = 14f
        val viewportWidthPx = 360f
        val sidePaddingPx = (viewportWidthPx - cardWidthPx) / 2f
        val viewportCenterPx = viewportWidthPx / 2f

        assertEquals(
            1,
            nearestCarouselIndexForScroll(
                scrollValue = 221,
                itemCount = 5,
                cardWidthPx = cardWidthPx,
                itemSpacingPx = itemSpacingPx,
                sidePaddingPx = sidePaddingPx,
                viewportCenterPx = viewportCenterPx,
            ),
        )
        assertEquals(
            2,
            nearestCarouselIndexForScroll(
                scrollValue = 347,
                itemCount = 5,
                cardWidthPx = cardWidthPx,
                itemSpacingPx = itemSpacingPx,
                sidePaddingPx = sidePaddingPx,
                viewportCenterPx = viewportCenterPx,
            ),
        )
    }
}
