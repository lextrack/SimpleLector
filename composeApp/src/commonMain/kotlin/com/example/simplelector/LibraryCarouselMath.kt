package com.example.simplelector

import kotlin.math.abs
import kotlin.math.roundToInt

internal fun centeredCarouselScrollFor(
    index: Int,
    itemCount: Int,
    cardWidthPx: Float,
    itemSpacingPx: Float,
    sidePaddingPx: Float,
    viewportCenterPx: Float,
    maxScroll: Int,
): Int {
    if (itemCount <= 0) return 0
    val safeIndex = index.coerceIn(0, itemCount - 1)
    val itemCenterPx = sidePaddingPx +
        safeIndex * (cardWidthPx + itemSpacingPx) +
        (cardWidthPx / 2f)
    return (itemCenterPx - viewportCenterPx)
        .roundToInt()
        .coerceIn(0, maxScroll)
}

internal fun nearestCarouselIndexForScroll(
    scrollValue: Int,
    itemCount: Int,
    cardWidthPx: Float,
    itemSpacingPx: Float,
    sidePaddingPx: Float,
    viewportCenterPx: Float,
): Int {
    if (itemCount <= 0) return 0
    return (0 until itemCount)
        .minByOrNull { index ->
            val itemCenterPx = sidePaddingPx +
                index * (cardWidthPx + itemSpacingPx) +
                (cardWidthPx / 2f) -
                scrollValue
            abs(itemCenterPx - viewportCenterPx)
        } ?: 0
}
