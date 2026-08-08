package com.peri.hardwareinformation.ui.about

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.peri.hardwareinformation.R
import com.peri.hardwareinformation.data.model.Section
import com.peri.hardwareinformation.data.model.UnavailableReason
import com.peri.hardwareinformation.data.model.section
import com.peri.hardwareinformation.ui.component.SectionCard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The one destination that is not a hardware category, so it is addressed by a reserved key rather
 * than by a collector id. Keeping it out of the catalog keeps it out of search and out of the
 * export, where an app version number would be an odd thing to find between the CPU and the GPU.
 */
const val ABOUT_DESTINATION = "about"

/**
 * What the app is, what it may do, and what it cannot.
 *
 * Its own screen rather than a sixteenth category: the identity block wants an icon and a paragraph,
 * neither of which the label/value schema renders, while the rows below reuse [SectionCard] so the
 * page still reads as part of the same app.
 *
 * Nothing here is asserted from a constant. The permission list is read back from the installed
 * package, so it cannot drift from the manifest, and the two claims that matter — no runtime prompt,
 * no network — are computed from what the platform reports rather than written down and trusted.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutPane(
    showBack: Boolean,
    rawValues: Boolean,
    attaching: Boolean,
    onBack: () -> Unit,
    onCopy: (label: String, value: String) -> Unit,
    onSendFeedback: (attachInventory: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val address = stringResource(R.string.feedback_email)
    val sections = remember(context, address) { aboutSections(context, address) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_title)) },
                navigationIcon = {
                    if (showBack) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.action_back),
                            )
                        }
                    }
                },
                // No raw-value toggle: nothing on this page has a second form to switch to.
            )
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                modifier = Modifier
                    // Narrower than the detail pane's cap: this page is prose and a short table,
                    // and neither reads well stretched across a tablet.
                    .widthIn(max = 600.dp)
                    .fillMaxHeight(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = padding.calculateBottomPadding() + 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { Identity() }
                items(sections.size) { index ->
                    SectionCard(
                        section = sections[index],
                        expanded = true,
                        onToggle = null,
                        onCopy = onCopy,
                        highlightedLabel = null,
                        rawValues = rawValues,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item { FeedbackActions(attaching = attaching, onSend = onSendFeedback) }
            }
        }
    }
}

/** The launcher icon and name, read from the installed package rather than from resources. */
@Composable
private fun Identity() {
    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { ICON_SIZE.roundToPx() }
    val icon = remember(context, sizePx) {
        runCatching {
            context.packageManager.getApplicationIcon(context.applicationInfo)
                .toBitmap(sizePx, sizePx)
                .asImageBitmap()
        }.getOrNull()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) {
            Image(
                bitmap = icon,
                // The app's name is written directly underneath.
                contentDescription = null,
                modifier = Modifier.size(ICON_SIZE),
            )
        }
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.about_tagline),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Sits below the cards rather than inside one: these are the only two things on the page that *do*
 * something, and a row that acts is not a row that reports.
 */
@Composable
private fun FeedbackActions(attaching: Boolean, onSend: (Boolean) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Button(
            onClick = { onSend(false) },
            enabled = !attaching,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.feedback_send))
        }
        TextButton(onClick = { onSend(true) }, enabled = !attaching) {
            if (attaching) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Text(stringResource(R.string.feedback_send_with_inventory))
            }
        }
    }
}

private fun aboutSections(context: Context, address: String): List<Section> {
    val packageManager = context.packageManager
    val info = runCatching {
        packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
    }.getOrNull()
    val declared = info?.requestedPermissions?.toList().orEmpty()

    return listOf(
        section("Application", fullWidth = true) {
            text("Version", info?.versionName)
            val code = info?.let { versionCode(it) }
            text("Version code", code?.toString())
            text("Package", context.packageName)
            val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
            text("Build", if (debuggable) "Debug" else "Release")
            text("Installed", info?.firstInstallTime?.let(::formatTimestamp))
            text("Updated", info?.lastUpdateTime?.let(::formatTimestamp))
            text("Installed from", installSource(context))
            text("Target SDK", context.applicationInfo.targetSdkVersion.toString())
            text("Minimum SDK", context.applicationInfo.minSdkVersion.toString())
        },
        section("Permissions", fullWidth = true) {
            if (declared.isEmpty()) {
                unavailable("Declared", UnavailableReason.READ_FAILED)
            } else {
                text("Declared", declared.size.toString())
                // Computed, not claimed: a dangerous permission is the only kind that can raise a
                // runtime dialog, so asking the platform for each protection level answers the
                // question the app would otherwise just be asserting about itself.
                flag("Runtime prompts", declared.any { isDangerous(packageManager, it) })
                declared.forEach { permission ->
                    val name = permission.substringAfterLast('.')
                    // Two lookups because not every permission is namespaced under
                    // `android.permission`: the one the build tools inject is declared under this
                    // app's own package name, so only the bare name is stable.
                    text(name, REASONS[permission] ?: REASONS[name] ?: UNEXPLAINED, raw = permission)
                }
            }
        },
        section("Privacy", fullWidth = true) {
            flag("Internet access", declared.contains(INTERNET))
            longText(
                "Data",
                "Every value in this app is read from this device's own APIs, system properties " +
                    "and kernel files while you are looking at it. Nothing is stored between " +
                    "launches and nothing is uploaded — without the internet permission above, " +
                    "the app could not upload anything even if it tried. Export and feedback both " +
                    "hand what they build to another app of your choosing, and neither sends " +
                    "until you press send there.",
            )
        },
        section("Feedback", fullWidth = true) {
            text("Email", address)
            longText(
                "How it works",
                "Both buttons open your own email app with the message, the app version and the " +
                    "device's build already written in. Read it, change it, delete any of it — " +
                    "nothing leaves this device until you send it yourself. The second button " +
                    "also attaches the full inventory, the same file the export button produces.",
            )
        },
        section("Open source") {
            text("Jetpack Compose", "Apache 2.0")
            text("AndroidX", "Apache 2.0")
            text("Material Symbols", "Apache 2.0")
            text("Kotlin", "Apache 2.0")
        },
    )
}

/** `versionCode` was widened in API 28 and the old field has been deprecated ever since. */
@Suppress("DEPRECATION")
private fun versionCode(info: PackageInfo): Long =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()

/**
 * The installer's package name, which for a sideloaded build is nothing at all — reported as such
 * rather than dressed up, since "no installer" is the honest answer for an app pushed over adb.
 */
private fun installSource(context: Context): String? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
    return runCatching {
        context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
    }.getOrNull() ?: "Sideloaded"
}

private fun isDangerous(packageManager: PackageManager, permission: String): Boolean = runCatching {
    val info = packageManager.getPermissionInfo(permission, 0)
    @Suppress("DEPRECATION")
    val protection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        info.protection
    } else {
        info.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE
    }
    protection == PermissionInfo.PROTECTION_DANGEROUS
}.getOrDefault(false)

private fun formatTimestamp(millis: Long): String? = millis.takeIf { it > 0 }?.let {
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(it))
}

private const val INTERNET = "android.permission.INTERNET"

/**
 * Why each permission is in the package, keyed by the permission itself so the reasons stay attached
 * to the list the platform reports rather than to a copy of it. Reading it back rather than writing
 * it out found two things a hand-written list would have got wrong: the build tools inject a
 * permission that appears in no manifest here, and `BLUETOOTH` is absent on Android 12 and up
 * because its `maxSdkVersion` keeps it out of the package the platform actually installed.
 *
 * A permission with no entry here says so rather than pretending, because an unexplained permission
 * is a thing to go and look at, not a thing to hide.
 */
private val REASONS = mapOf(
    "android.permission.ACCESS_WIFI_STATE" to "Wi-Fi band and standard queries",
    "android.permission.NFC" to "NFC adapter capabilities",
    "android.permission.HIGH_SAMPLING_RATE_SENSORS" to "True sensor sampling rates, not the 200 Hz cap",
    "android.permission.BLUETOOTH" to "Bluetooth capabilities on Android 11 and below",
    // Keyed bare: the build tools declare this one under the app's own package name.
    "DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" to
        "Added by the build tools so the battery broadcast receiver stays private to this app",
)

private const val UNEXPLAINED = "No reason recorded"

private val ICON_SIZE = 88.dp
