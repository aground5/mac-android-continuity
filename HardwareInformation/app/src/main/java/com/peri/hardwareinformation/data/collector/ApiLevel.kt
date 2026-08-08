package com.peri.hardwareinformation.data.collector

import android.os.Build

/**
 * Evaluates [value] only when the platform is new enough, otherwise null.
 *
 * minSdk here is 29 while plenty of capability queries arrived in 30–34. Feeding the null straight
 * into `text`/`flag`/`tokens` turns "this API did not exist yet" into a
 * [com.peri.hardwareinformation.data.model.UnavailableReason.NOT_SUPPORTED] row, which is exactly
 * what it means — and keeps collectors free of `if (SDK_INT >= …) … else unavailable(…)` pairs.
 *
 * Note that this is only needed for *methods*. Integer and string constants added in later API
 * levels are inlined by the compiler and are safe to reference from a `when` on any device.
 */
internal inline fun <T> sinceApi(level: Int, value: () -> T): T? =
    if (Build.VERSION.SDK_INT >= level) value() else null
