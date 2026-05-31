package com.example.simplelector

import java.util.Locale

actual fun currentLanguageTag(): String = Locale.getDefault().toLanguageTag()

actual fun isDesktopPlatform(): Boolean = false
