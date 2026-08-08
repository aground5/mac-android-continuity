package com.peri.hardwareinformation.ui.about

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/**
 * Opens the user's own email app with the message already written.
 *
 * The app never sends anything itself, and it could not if it wanted to — it holds no internet
 * permission, which the Privacy section proves rather than promises. Everything here is handed to
 * another app, and the user reads and approves it in that app's composer before it goes anywhere.
 *
 * Two intents rather than one, because the tidy single-intent version does not work. `ACTION_SEND`
 * with a `mailto:` selector is the documented way to ask for "mail apps only, with an attachment",
 * and on a Galaxy S25 it is refused before it reaches a chooser — the activity manager returns a
 * failure code and the caller gets an ActivityNotFoundException on a device with three mail apps
 * installed. So the common case uses plain `ACTION_SENDTO`, which resolves correctly, and only the
 * attachment case falls back to a chooser.
 */
internal fun Context.sendFeedback(
    address: String,
    subject: String,
    attachment: File?,
    failureMessage: String,
) {
    val body = feedbackBody(this)
    val attachmentUri = attachment?.let {
        runCatching { FileProvider.getUriForFile(this, "$packageName.fileprovider", it) }.getOrNull()
    }
    val intent = if (attachmentUri == null) {
        composeIntent(address, subject, body)
    } else {
        // A chooser always resolves, so this branch cannot throw; it is wider than mail apps only,
        // but it is the same sheet the export button already opens.
        Intent.createChooser(attachIntent(address, subject, body, attachmentUri), subject)
    }
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, failureMessage, Toast.LENGTH_SHORT).show()
    }
}

/**
 * Subject and body ride in the URI *and* in the extras: the `mailto:` query is what the intent
 * filter matches on, but not every client reads it back out, and most read the extras.
 */
private fun composeIntent(address: String, subject: String, body: String): Intent =
    Intent(Intent.ACTION_SENDTO).apply {
        data = Uri.parse(
            "mailto:${Uri.encode(address)}" +
                "?subject=${Uri.encode(subject)}" +
                "&body=${Uri.encode(body)}",
        )
        putExtra(Intent.EXTRA_EMAIL, arrayOf(address))
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, body)
    }

private fun attachIntent(address: String, subject: String, body: String, uri: Uri): Intent =
    Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_EMAIL, arrayOf(address))
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, body)
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

/**
 * Blank lines first so the composer's cursor lands where the user types, with the details below a
 * rule they can delete — the report is more useful with them and still sendable without.
 *
 * Deliberately short. A bug in a hardware inventory needs to know which build produced it, and the
 * fingerprint carries brand, device, release, build id and tags in one line; anything more belongs
 * in the attachment the user opts into.
 */
private fun feedbackBody(context: Context): String {
    val info = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0)
    }.getOrNull()
    val name = context.getString(com.peri.hardwareinformation.R.string.app_name)
    return buildString {
        append("\n\n\n")
        append("————————\n")
        append("$name ${info?.versionName.orEmpty()} (${info?.let(::versionCodeOf) ?: "?"})\n")
        append("${Build.MANUFACTURER} ${Build.MODEL}\n")
        append("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        append("${Build.FINGERPRINT}\n")
    }
}

@Suppress("DEPRECATION")
private fun versionCodeOf(info: PackageInfo): Long =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
