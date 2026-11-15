package koharubiyori.starlight.screen.settings

import androidx.activity.ComponentActivity
import koharubiyori.starlight.store.proto.SettingsStoreData
import koharubiyori.starlight.store.proto.SettingsStoreData.Bitrates
import kotlin.math.abs
import kotlin.math.roundToInt

private val bitrateResolutions = VideoResolutions.entries.filter { it.bitrateAt60Fps != null }

private fun supportedFpsFromFps(fps: Int): SupportedFps =
  SupportedFps.entries.firstOrNull { it.fps == fps } ?: SupportedFps.FPS_60

fun SettingsStoreData.automaticBitrate(): Int =
  (bitrateAt60Fps(width, height) * supportedFpsFromFps(fps).bitrateFactor).roundToInt()

val SettingsStoreData.bitrate: Int
  get() = if (customBitrate) {
    val selectedBitrate = when (matchVideoResolution(width, height)) {
      VideoResolutions.P720 -> bitrates.p720
      VideoResolutions.P1080 -> bitrates.p1080
      VideoResolutions.P1440 -> bitrates.p1440
      VideoResolutions.K4 -> bitrates.p4K
      else -> bitrates.native
    }
    if (selectedBitrate > 0) selectedBitrate else automaticBitrate()
  } else {
    automaticBitrate()
  }

fun defaultBitrates(activity: ComponentActivity, fps: Int): Bitrates {
  val factor = supportedFpsFromFps(fps).bitrateFactor
  val nativeSize = SettingsScreenUtils.getNativeResolution(activity, true)
  return Bitrates.newBuilder()
    .setP720((VideoResolutions.P720.bitrateAt60Fps!! * factor).roundToInt())
    .setP1080((VideoResolutions.P1080.bitrateAt60Fps!! * factor).roundToInt())
    .setP1440((VideoResolutions.P1440.bitrateAt60Fps!! * factor).roundToInt())
    .setP4K((VideoResolutions.K4.bitrateAt60Fps!! * factor).roundToInt())
    .setNative((bitrateAt60Fps(nativeSize.width, nativeSize.height) * factor).roundToInt())
    .build()
}

fun Bitrates.isEmpty(): Boolean =
  !hasP720() && !hasP1080() && !hasP1440() && !hasP4K() && !hasNative()

private fun bitrateAt60Fps(width: Int, height: Int): Float {
  val pixelCount = width.toLong() * height
  val closestResolution = bitrateResolutions.minBy { resolution ->
    abs(
      pixelCount -
        resolution.width!!.toLong() * resolution.height!!
    )
  }
  val closestPixelCount = closestResolution.width!!.toLong() * closestResolution.height!!
  return closestResolution.bitrateAt60Fps!! * pixelCount.toFloat() / closestPixelCount
}

private fun matchVideoResolution(width: Int, height: Int): VideoResolutions? =
  VideoResolutions.entries.firstOrNull { it.width == width && it.height == height }
