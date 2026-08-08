package com.peri.hardwareinformation.data.collector

import android.content.Context
import android.content.pm.PackageManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.opengl.GLES30
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeveloperBoard
import com.peri.hardwareinformation.data.formatDeqpLevel
import com.peri.hardwareinformation.data.formatGlEsVersion
import com.peri.hardwareinformation.data.formatGrouped
import com.peri.hardwareinformation.data.formatVulkanVersion
import com.peri.hardwareinformation.data.model.Section
import com.peri.hardwareinformation.data.model.UnavailableReason
import com.peri.hardwareinformation.data.model.section

/**
 * The GPU is the one category that cannot simply be read: `GL_RENDERER` and friends only exist once
 * a GL context is current, so this collector spins up a 1×1 off-screen pbuffer context, queries the
 * strings, and tears it down. That runs on the collector's IO dispatcher, never the main thread.
 *
 * Vulkan has no Java API at all. Its version and level are instead recovered from the
 * [PackageManager] feature list, where the platform publishes them as packed version codes.
 */
class GpuCollector(context: Context) : Collector {
    override val id = "gpu"
    override val title = "GPU"
    override val icon = Icons.Filled.DeveloperBoard

    private val appContext = context.applicationContext

    override suspend fun sections(): List<Section> {
        val gl = queryOpenGl()
        return listOf(
            section("Renderer", fullWidth = true) {
                if (gl == null) {
                    unavailable("Vendor", UnavailableReason.READ_FAILED)
                    unavailable("Renderer", UnavailableReason.READ_FAILED)
                    unavailable("OpenGL ES version", UnavailableReason.READ_FAILED)
                } else {
                    text("Vendor", gl.vendor)
                    text("Renderer", gl.renderer)
                    text("OpenGL ES version", gl.version)
                    text("Shading language", gl.shadingLanguage)
                }
                // Android lets the GPU driver ship as an app the store can update, so the driver
                // running here need not be the one in the system image. These name the packages the
                // platform is configured to look for; nothing here says which one is loaded.
                val properties = readSystemProperties()
                tokens(
                    "Updatable driver packages",
                    listOfNotNull(properties["ro.gfx.driver.0"], properties["ro.gfx.driver.1"]),
                )
                // The version the platform itself declares — lets the driver string be checked.
                val declaredEs = declaredEsVersion()
                text(
                    "Declared ES version",
                    declaredEs?.let(::formatGlEsVersion),
                    raw = declaredEs?.let { String.format(java.util.Locale.US, "0x%X", it) },
                )
                flag(
                    "Extension pack (AEP)",
                    appContext.packageManager
                        .hasSystemFeature(PackageManager.FEATURE_OPENGLES_EXTENSION_PACK),
                )
            },
            section("EGL") {
                if (gl == null) {
                    unavailable("Version", UnavailableReason.READ_FAILED)
                } else {
                    text("Version", gl.eglVersion)
                    text("Vendor", gl.eglVendor)
                    tokens("Client APIs", gl.eglClientApis?.split(' ')?.filter(String::isNotBlank))
                }
            },
            section("Limits") {
                if (gl == null) {
                    unavailable("Maximum texture size", UnavailableReason.READ_FAILED)
                } else {
                    text("Maximum texture size", gl.maxTextureSize?.let { "$it px" }, raw = gl.maxTextureSize?.toString())
                    text("Maximum cube map size", gl.maxCubeMapSize?.let { "$it px" }, raw = gl.maxCubeMapSize?.toString())
                    text("Maximum renderbuffer size", gl.maxRenderbufferSize?.let { "$it px" }, raw = gl.maxRenderbufferSize?.toString())
                    text("Maximum viewport", gl.maxViewport)
                    text("Texture image units", gl.maxTextureUnits?.toString())
                    text("Vertex attributes", gl.maxVertexAttribs?.toString())
                }
            },
            section("Vulkan") {
                val version = featureVersion(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION)
                val level = featureVersion(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL)
                flag("Supported", version != null)
                text("Version", version?.let(::formatVulkanVersion), raw = version?.toString())
                text("Hardware level", level?.toString())
                flag(
                    "Compute",
                    featureVersion(PackageManager.FEATURE_VULKAN_HARDWARE_COMPUTE)?.let { true },
                )
                val deqp = featureVersion(PackageManager.FEATURE_VULKAN_DEQP_LEVEL)
                text("dEQP level", deqp?.let(::formatDeqpLevel), raw = deqp?.toString())
            },
            section("Extensions") {
                val extensions = gl?.extensions?.split(' ')?.filter(String::isNotBlank).orEmpty()
                val count = extensions.size.takeIf { it > 0 }
                text("Count", count?.toLong()?.let(::formatGrouped), raw = count?.toString())
                longText("Names", extensions.sorted().joinToString(" ").takeIf(String::isNotEmpty))
            },
        )
    }

    private fun featureVersion(name: String): Int? = appContext.packageManager
        .systemAvailableFeatures
        .firstOrNull { it.name == name }
        ?.version

    /** What the platform advertises, e.g. 0x30002 for ES 3.2. Zero means it never said. */
    private fun declaredEsVersion(): Int? = appContext
        .getSystemService(android.app.ActivityManager::class.java)
        ?.deviceConfigurationInfo?.reqGlEsVersion
        ?.takeIf { it > 0 }

    private class GlInfo(
        val vendor: String?,
        val renderer: String?,
        val version: String?,
        val shadingLanguage: String?,
        val extensions: String?,
        val eglVersion: String?,
        val eglVendor: String?,
        val eglClientApis: String?,
        val maxTextureSize: Int?,
        val maxCubeMapSize: Int?,
        val maxRenderbufferSize: Int?,
        val maxViewport: String?,
        val maxTextureUnits: Int?,
        val maxVertexAttribs: Int?,
    )

    /**
     * Creates a throwaway off-screen context purely to read the driver strings. Every step can fail
     * on a device with a broken or software-only driver, so the whole thing collapses to null and the
     * category reports honestly rather than crashing.
     */
    private fun queryOpenGl(): GlInfo? = runCatching {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) return null
        val eglVersion = IntArray(2)
        if (!EGL14.eglInitialize(display, eglVersion, 0, eglVersion, 1)) return null

        var context = EGL14.EGL_NO_CONTEXT
        var surface = EGL14.EGL_NO_SURFACE
        try {
            val configs = arrayOfNulls<EGLConfig>(1)
            val configCount = IntArray(1)
            val chosen = EGL14.eglChooseConfig(
                display,
                intArrayOf(
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_NONE,
                ),
                0, configs, 0, 1, configCount, 0,
            )
            if (!chosen || configCount[0] == 0) return null

            context = EGL14.eglCreateContext(
                display,
                configs[0],
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE),
                0,
            ).takeIf { it != EGL14.EGL_NO_CONTEXT }
                ?: EGL14.eglCreateContext(
                    display,
                    configs[0],
                    EGL14.EGL_NO_CONTEXT,
                    intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
                    0,
                )
            if (context == EGL14.EGL_NO_CONTEXT) return null

            surface = EGL14.eglCreatePbufferSurface(
                display,
                configs[0],
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
                0,
            )
            if (surface == EGL14.EGL_NO_SURFACE) return null
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) return null

            val viewport = intValues(GLES20.GL_MAX_VIEWPORT_DIMS, 2)
            GlInfo(
                vendor = GLES20.glGetString(GLES20.GL_VENDOR),
                renderer = GLES20.glGetString(GLES20.GL_RENDERER),
                version = GLES20.glGetString(GLES20.GL_VERSION),
                shadingLanguage = GLES20.glGetString(GLES20.GL_SHADING_LANGUAGE_VERSION),
                extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS),
                eglVersion = EGL14.eglQueryString(display, EGL14.EGL_VERSION),
                eglVendor = EGL14.eglQueryString(display, EGL14.EGL_VENDOR),
                eglClientApis = EGL14.eglQueryString(display, EGL14.EGL_CLIENT_APIS),
                maxTextureSize = intValue(GLES20.GL_MAX_TEXTURE_SIZE),
                maxCubeMapSize = intValue(GLES20.GL_MAX_CUBE_MAP_TEXTURE_SIZE),
                maxRenderbufferSize = intValue(GLES20.GL_MAX_RENDERBUFFER_SIZE),
                maxViewport = viewport?.let { "${it[0]} × ${it[1]}" },
                maxTextureUnits = intValue(GLES30.GL_MAX_TEXTURE_IMAGE_UNITS),
                maxVertexAttribs = intValue(GLES20.GL_MAX_VERTEX_ATTRIBS),
            )
        } finally {
            EGL14.eglMakeCurrent(
                display,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT,
            )
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
    }.getOrNull()

    private fun intValue(name: Int): Int? = intValues(name, 1)?.first()

    private fun intValues(name: Int, count: Int): IntArray? {
        val values = IntArray(count)
        GLES20.glGetIntegerv(name, values, 0)
        return values.takeIf { GLES20.glGetError() == GLES20.GL_NO_ERROR }
    }
}
