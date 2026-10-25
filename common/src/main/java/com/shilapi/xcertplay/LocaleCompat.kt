package com.shilapi.xcertplay

import android.content.res.Configuration
import android.os.Build
import java.util.Locale

/** The first configured locale. [Configuration.getLocales] needs API 24; Android 6 has a single locale. */
internal val Configuration.primaryLocale: Locale
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) locales[0] else @Suppress("DEPRECATION") locale

/** Copies [source]'s locales and the layout direction of its first locale, which may be unset. */
internal fun Configuration.setLocalesFrom(source: Configuration) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        setLocales(source.locales)
        setLayoutDirection(source.locales[0])
    } else {
        // Before API 24 setLocale also sets the layout direction.
        @Suppress("DEPRECATION") setLocale(source.locale)
    }
}
