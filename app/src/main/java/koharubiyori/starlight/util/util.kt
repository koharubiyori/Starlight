package koharubiyori.starlight.util

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.RoundedCorner
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import koharubiyori.starlight.R
import koharubiyori.starlight.nvstream.AndroidCryptoProvider
import koharubiyori.starlight.nvstream.http.NvHTTP
import koharubiyori.starlight.screen.home.util.ComputerDetailsEx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.xmlpull.v1.XmlPullParser
import timber.log.Timber
import java.net.URI
import java.util.Locale

@Target(AnnotationTarget.CLASS)
annotation class ProguardIgnore

val globalMainCoroutineScope = CoroutineScope(Dispatchers.Main)
val globalDefaultCoroutineScope = CoroutineScope(Dispatchers.Default)
val globalIoContinueScope = CoroutineScope(Dispatchers.IO)

fun uniqueId(context: Context): String {
  @SuppressLint("HardwareIds")
  return Settings.Secure.getString(
    context.contentResolver,
    Settings.Secure.ANDROID_ID
  )
}

val glRenderer: String by lazy {
  val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
  val version = IntArray(2)
  EGL14.eglInitialize(display, version, 0, version, 1)

  val attribs = intArrayOf(
    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
    EGL14.EGL_NONE
  )
  val configs = arrayOfNulls<EGLConfig>(1)
  val numConfigs = IntArray(1)
  EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, numConfigs, 0)

  val contextAttribs = intArrayOf(
    EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
    EGL14.EGL_NONE
  )
  val context = EGL14.eglCreateContext(
    display, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0
  )

  EGL14.eglMakeCurrent(
    display,
    EGL14.EGL_NO_SURFACE,
    EGL14.EGL_NO_SURFACE,
    context
  )

  val renderer = GLES20.glGetString(GLES20.GL_RENDERER)

  EGL14.eglDestroyContext(display, context)
  EGL14.eglTerminate(display)

  renderer
}

private const val dividerLine = "----------------------"

fun debugPrint(vararg value: Any?, divider: Boolean = true) {
  if (divider) Timber.w("$dividerLine DEBUG PRINT START $dividerLine")
  Timber.w(value.joinToString(separator = ", ") { it.toString() })
  if (divider) Timber.w("$dividerLine DEBUG PRINT END $dividerLine")
}

enum class VibrationType {
  Click,
  LongPress,
  Success,
  Error,
  Custom
}

fun vibrate(context: Context, type: VibrationType = VibrationType.Click) {
  val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
  if (!vibrator.hasVibrator()) return

  val effect = when (type) {
    VibrationType.Click ->
      VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE)

    VibrationType.LongPress ->
      VibrationEffect.createOneShot(70, VibrationEffect.DEFAULT_AMPLITUDE)

    VibrationType.Success ->
      VibrationEffect.createWaveform(longArrayOf(0, 40, 50, 40), -1)

    VibrationType.Error ->
      VibrationEffect.createWaveform(longArrayOf(0, 30, 40, 30, 50, 60), -1)

    VibrationType.Custom ->
      VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE)
  }

  vibrator.vibrate(effect)
}

fun <T> debounce(
  waitMs: Long = 300L,
  coroutineScope: CoroutineScope = globalMainCoroutineScope,
  action: (T) -> Unit
): (T) -> Unit {
  var job: Job? = null
  return { param: T ->
    job?.cancel()
    job = coroutineScope.launch {
      delay(waitMs)
      action(param)
    }
  }
}

fun Activity.updateLocale(locale: Locale) {
  val config = Configuration(resources.configuration)
  config.setLocale(locale)

  resources.updateConfiguration(config, resources.displayMetrics)
}

fun canGetRoot(): Boolean {
  return try {
    val process = Runtime.getRuntime().exec("su")
    val output = process.outputStream
    output.write("exit\n".toByteArray())
    output.flush()
    process.waitFor() == 0
  } catch (e: Exception) {
    false
  }
}

fun Color.adjustByHSL(saturation: Float, lightness: Float): Color {
  val hsl = FloatArray(3)
  ColorUtils.colorToHSL(this.toArgb(), hsl)
  return Color.hsl(hsl[0], saturation, lightness)
}

fun createNvHttp(context: Context, computeDetails: ComputerDetailsEx) = NvHTTP(
  computeDetails.activeAddress,
  computeDetails.httpsPort, uniqueId(context), computeDetails.serverCert,
  AndroidCryptoProvider(context)
)