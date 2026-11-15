package koharubiyori.starlight.screen.streaming.util.video

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.pm.ConfigurationInfo
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import timber.log.Timber
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.util.Collections
import java.util.LinkedList
import java.util.Locale
import java.util.regex.Pattern

object MediaCodecHelper {

  private val preferredDecoders: MutableList<String>

  private val blacklistedDecoderPrefixes: MutableList<String>
  private val spsFixupBitstreamFixupDecoderPrefixes: MutableList<String>
  private val blacklistedAdaptivePlaybackPrefixes: MutableList<String>
  private val baselineProfileHackPrefixes: MutableList<String>
  private val directSubmitPrefixes: MutableList<String>
  private val constrainedHighProfilePrefixes: MutableList<String>
  private val whitelistedHevcDecoders: MutableList<String>
  private val refFrameInvalidationAvcPrefixes: MutableList<String>
  private val refFrameInvalidationHevcPrefixes: MutableList<String>
  private val useFourSlicesPrefixes: MutableList<String>
  private val qualcommDecoderPrefixes: MutableList<String>
  private val kirinDecoderPrefixes: MutableList<String>
  private val exynosDecoderPrefixes: MutableList<String>
  private val amlogicDecoderPrefixes: MutableList<String>
  private val knownVendorLowLatencyOptions: MutableList<String>

  val SHOULD_BYPASS_SOFTWARE_BLOCK: Boolean =
    Build.HARDWARE.equals("ranchu", ignoreCase = true) ||
        Build.HARDWARE.equals("cheets", ignoreCase = true) ||
        Build.BRAND.equals("Android-x86", ignoreCase = true)

  private var isLowEndSnapdragon = false
  private var isAdreno620 = false
  private var initialized = false

  init {
    directSubmitPrefixes = LinkedList()
    directSubmitPrefixes.add("omx.qcom")
    directSubmitPrefixes.add("omx.sec")
    directSubmitPrefixes.add("omx.exynos")
    directSubmitPrefixes.add("omx.intel")
    directSubmitPrefixes.add("omx.brcm")
    directSubmitPrefixes.add("omx.TI")
    directSubmitPrefixes.add("omx.arc")
    directSubmitPrefixes.add("omx.nvidia")
    directSubmitPrefixes.add("c2.")
  }

  init {
    refFrameInvalidationAvcPrefixes = LinkedList()
    refFrameInvalidationHevcPrefixes = LinkedList()
    refFrameInvalidationHevcPrefixes.add("omx.exynos")
    refFrameInvalidationHevcPrefixes.add("c2.exynos")
  }

  init {
    preferredDecoders = LinkedList()
  }

  init {
    blacklistedDecoderPrefixes = LinkedList()
    if (!SHOULD_BYPASS_SOFTWARE_BLOCK) {
      blacklistedDecoderPrefixes.add("omx.google")
      blacklistedDecoderPrefixes.add("AVCDecoder")
    }
    blacklistedDecoderPrefixes.add("OMX.qcom.video.decoder.hevcswvdec")
    blacklistedDecoderPrefixes.add("OMX.SEC.hevc.sw.dec")
  }

  init {
    spsFixupBitstreamFixupDecoderPrefixes = LinkedList()
    spsFixupBitstreamFixupDecoderPrefixes.add("omx.nvidia")
    spsFixupBitstreamFixupDecoderPrefixes.add("omx.qcom")
    spsFixupBitstreamFixupDecoderPrefixes.add("omx.brcm")

    baselineProfileHackPrefixes = LinkedList()
    baselineProfileHackPrefixes.add("omx.intel")

    blacklistedAdaptivePlaybackPrefixes = LinkedList()
    blacklistedAdaptivePlaybackPrefixes.add("omx.intel")
    blacklistedAdaptivePlaybackPrefixes.add("omx.mtk")

    constrainedHighProfilePrefixes = LinkedList()
    constrainedHighProfilePrefixes.add("omx.intel")
  }

  init {
    whitelistedHevcDecoders = LinkedList()
    if (Build.HARDWARE.equals("ranchu", ignoreCase = true)) {
      whitelistedHevcDecoders.add("omx.google")
    }
    whitelistedHevcDecoders.add("omx.exynos")
    if (!Build.DEVICE.equals("shieldtablet", ignoreCase = true) &&
      !Build.DEVICE.equals("mocha", ignoreCase = true)
    ) {
      whitelistedHevcDecoders.add("omx.nvidia")
    }
    if (Build.DEVICE.startsWith("BRAVIA_")) {
      whitelistedHevcDecoders.add("omx.mtk")
    }
    if (!Build.DEVICE.equals(
        "sabrina",
        ignoreCase = true
      )
    ) {
      whitelistedHevcDecoders.add("omx.amlogic")
    }
    whitelistedHevcDecoders.add("omx.realtek")
    whitelistedHevcDecoders.add("c2.")
  }

  init {
    useFourSlicesPrefixes = LinkedList()
    useFourSlicesPrefixes.add("omx.google")
    useFourSlicesPrefixes.add("AVCDecoder")
    useFourSlicesPrefixes.add("omx.ffmpeg")
    useFourSlicesPrefixes.add("c2.android")
  }

  init {
    knownVendorLowLatencyOptions = LinkedList()
    knownVendorLowLatencyOptions.add("vendor.qti-ext-dec-low-latency.enable")
    knownVendorLowLatencyOptions.add("vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-req")
    knownVendorLowLatencyOptions.add("vendor.rtc-ext-dec-low-latency.enable")
    knownVendorLowLatencyOptions.add("vendor.low-latency.enable")
  }

  init {
    qualcommDecoderPrefixes = LinkedList()
    qualcommDecoderPrefixes.add("omx.qcom")
    qualcommDecoderPrefixes.add("c2.qti")
  }

  init {
    kirinDecoderPrefixes = LinkedList()
    kirinDecoderPrefixes.add("omx.hisi")
    kirinDecoderPrefixes.add("c2.hisi")
  }

  init {
    exynosDecoderPrefixes = LinkedList()
    exynosDecoderPrefixes.add("omx.exynos")
    exynosDecoderPrefixes.add("c2.exynos")
  }

  init {
    amlogicDecoderPrefixes = LinkedList()
    amlogicDecoderPrefixes.add("omx.amlogic")
    amlogicDecoderPrefixes.add("c2.amlogic")
  }

  private fun isPowerVR(glRenderer: String): Boolean {
    return glRenderer.lowercase().contains("powervr")
  }

  private fun getAdrenoVersionString(glRenderer: String): String? {
    var renderer = glRenderer.lowercase().trim()
    if (!renderer.contains("adreno")) {
      return null
    }
    val modelNumberPattern = Pattern.compile("(.*)([0-9]{3})(.*)")
    val matcher = modelNumberPattern.matcher(renderer)
    if (!matcher.matches()) {
      return null
    }
    val modelNumber = matcher.group(2)
    Timber.i("Found Adreno GPU: $modelNumber")
    return modelNumber
  }

  private fun isLowEndSnapdragonRenderer(glRenderer: String): Boolean {
    val modelNumber = getAdrenoVersionString(glRenderer) ?: return false
    return modelNumber[1] == '0'
  }

  private fun getAdrenoRendererModelNumber(glRenderer: String): Int {
    val modelNumber = getAdrenoVersionString(glRenderer) ?: return -1
    return modelNumber.toInt()
  }

  private fun isGLES31SnapdragonRenderer(glRenderer: String): Boolean {
    return getAdrenoRendererModelNumber(glRenderer) >= 400
  }

  fun initialize(context: Context, glRenderer: String) {
    if (initialized) {
      return
    }

    if (context.packageManager.hasSystemFeature("amazon.hardware.fire_tv") ||
      Build.MANUFACTURER.equals("Amazon", ignoreCase = true)
    ) {
      whitelistedHevcDecoders.add("omx.mtk")
      refFrameInvalidationHevcPrefixes.add("omx.mtk")
      refFrameInvalidationHevcPrefixes.add("c2.mtk")
      whitelistedHevcDecoders.add("omx.amlogic")
      refFrameInvalidationHevcPrefixes.add("omx.amlogic")
      refFrameInvalidationHevcPrefixes.add("c2.amlogic")
    }

    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val configInfo = activityManager.deviceConfigurationInfo
    if (configInfo.reqGlEsVersion != ConfigurationInfo.GL_ES_VERSION_UNDEFINED) {
      Timber.i("OpenGL ES version: " + configInfo.reqGlEsVersion)
      isLowEndSnapdragon = isLowEndSnapdragonRenderer(glRenderer)
      isAdreno620 = getAdrenoRendererModelNumber(glRenderer) == 620

      if (configInfo.reqGlEsVersion >= 0x30000) {
        Timber.i("Added omx.nvidia/c2.nvidia to reference frame invalidation support list")
        refFrameInvalidationAvcPrefixes.add("omx.nvidia")
        if (!Build.DEVICE.equals(
            "dragon",
            ignoreCase = true
          )
        ) {
          refFrameInvalidationHevcPrefixes.add("omx.nvidia")
        }
        refFrameInvalidationAvcPrefixes.add("c2.nvidia")
        refFrameInvalidationHevcPrefixes.add("c2.nvidia")

        Timber.i("Added omx.qcom/c2.qti to reference frame invalidation support list")
        refFrameInvalidationAvcPrefixes.add("omx.qcom")
        refFrameInvalidationHevcPrefixes.add("omx.qcom")
        refFrameInvalidationAvcPrefixes.add("c2.qti")
        refFrameInvalidationHevcPrefixes.add("c2.qti")
      }

      if (isGLES31SnapdragonRenderer(glRenderer)) {
        Timber.i("Added omx.qcom/c2.qti to HEVC decoders based on GLES 3.1+ support")
        whitelistedHevcDecoders.add("omx.qcom")
        whitelistedHevcDecoders.add("c2.qti")
      } else {
        blacklistedDecoderPrefixes.add("OMX.qcom.video.decoder.hevc")
        useFourSlicesPrefixes.add("omx.qcom")
      }

      if (isPowerVR(glRenderer)) {
        Timber.i("Added omx.mtk to HEVC decoders based on PowerVR GPU")
        whitelistedHevcDecoders.add("omx.mtk")
        if (glRenderer.contains("GX6")) {
          Timber.i("Added omx.mtk/c2.mtk to RFI list for HEVC")
          refFrameInvalidationHevcPrefixes.add("omx.mtk")
          refFrameInvalidationHevcPrefixes.add("c2.mtk")
        }
      }
    }

    initialized = true
  }

  private fun isDecoderInList(decoderList: List<String>, decoderName: String): Boolean {
    if (!initialized) {
      throw IllegalStateException("MediaCodecHelper must be initialized before use")
    }
    for (badPrefix in decoderList) {
      if (decoderName.length >= badPrefix.length) {
        val prefix = decoderName.substring(0, badPrefix.length)
        if (prefix.equals(badPrefix, ignoreCase = true)) {
          return true
        }
      }
    }
    return false
  }

  private fun decoderSupportsAndroidRLowLatency(
    decoderInfo: MediaCodecInfo,
    mimeType: String
  ): Boolean {
    return try {
      decoderInfo.getCapabilitiesForType(mimeType)
        .isFeatureSupported(CodecCapabilities.FEATURE_LowLatency).also {
        if (it) Timber.i("Low latency decoding mode supported (FEATURE_LowLatency)")
      }
    } catch (e: Exception) {
      e.printStackTrace()
      false
    }
  }

  private fun decoderSupportsKnownVendorLowLatencyOption(decoderName: String): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      var testCodec: MediaCodec? = null
      return try {
        testCodec = MediaCodec.createByCodecName(decoderName)
        var found = false
        for (supportedOption in testCodec!!.supportedVendorParameters) {
          for (knownLowLatencyOption in knownVendorLowLatencyOptions) {
            if (supportedOption.equals(knownLowLatencyOption, ignoreCase = true)) {
              Timber.i("$decoderName supports known low latency option: $supportedOption")
              found = true
              break
            }
          }
          if (found) break
        }
        found
      } catch (e: Exception) {
        e.printStackTrace()
        false
      } finally {
        testCodec?.release()
      }
    }
    return false
  }

  private fun decoderSupportsMaxOperatingRate(decoderName: String): Boolean {
    return isDecoderInList(qualcommDecoderPrefixes, decoderName) && !isAdreno620
  }

  fun setDecoderLowLatencyOptions(
    videoFormat: MediaFormat,
    decoderInfo: MediaCodecInfo,
    tryNumber: Int
  ): Boolean {
    var setNewOption = false

    when {
      tryNumber < 1 -> {
        videoFormat.setInteger("low-latency", 1)
        setNewOption = true
        if (decoderSupportsAndroidRLowLatency(
            decoderInfo,
            videoFormat.getString(MediaFormat.KEY_MIME)!!
          )
        ) {
          return true
        }
      }

      tryNumber < 2 -> {
        videoFormat.setInteger("vdec-lowlatency", 1)
        setNewOption = true
      }

      tryNumber < 3 -> {
        if (decoderSupportsMaxOperatingRate(decoderInfo.name)) {
          videoFormat.setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())
          setNewOption = true
        } else
          videoFormat.setInteger(MediaFormat.KEY_PRIORITY, 0)
          setNewOption = true
      }
    }

    when {
      isDecoderInList(qualcommDecoderPrefixes, decoderInfo.name) -> {
        if (tryNumber < 4) {
          videoFormat.setInteger("vendor.qti-ext-dec-picture-order.enable", 1)
          setNewOption = true
        }
        if (tryNumber < 5) {
          videoFormat.setInteger("vendor.qti-ext-dec-low-latency.enable", 1)
          setNewOption = true
        }
      }

      isDecoderInList(kirinDecoderPrefixes, decoderInfo.name) -> {
        if (tryNumber < 4) {
          videoFormat.setInteger(
            "vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-req",
            1
          )
          videoFormat.setInteger(
            "vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-rdy",
            -1
          )
          setNewOption = true
        }
      }

      isDecoderInList(exynosDecoderPrefixes, decoderInfo.name) -> {
        if (tryNumber < 4) {
          videoFormat.setInteger("vendor.rtc-ext-dec-low-latency.enable", 1)
          setNewOption = true
        }
      }

      isDecoderInList(amlogicDecoderPrefixes, decoderInfo.name) -> {
        if (tryNumber < 4) {
          videoFormat.setInteger("vendor.low-latency.enable", 1)
          setNewOption = true
        }
      }
    }

    return setNewOption
  }

  fun decoderSupportsFusedIdrFrame(decoderInfo: MediaCodecInfo, mimeType: String): Boolean {
    return try {
      decoderInfo.getCapabilitiesForType(mimeType)
        .isFeatureSupported(CodecCapabilities.FEATURE_AdaptivePlayback).also {
          if (it) Timber.i("Decoder supports fused IDR frames (FEATURE_AdaptivePlayback)")
        }
    } catch (e: Exception) {
      e.printStackTrace()
      false
    }
  }

  fun decoderSupportsAdaptivePlayback(decoderInfo: MediaCodecInfo, mimeType: String): Boolean {
    if (isDecoderInList(blacklistedAdaptivePlaybackPrefixes, decoderInfo.name)) {
      Timber.i("Decoder blacklisted for adaptive playback")
      return false
    }
    return try {
      decoderInfo.getCapabilitiesForType(mimeType)
        .isFeatureSupported(CodecCapabilities.FEATURE_AdaptivePlayback).also {
          if (it) Timber.i("Adaptive playback supported (FEATURE_AdaptivePlayback)")
        }
    } catch (e: Exception) {
      e.printStackTrace()
      false
    }
  }

  fun decoderNeedsConstrainedHighProfile(decoderName: String): Boolean {
    return isDecoderInList(constrainedHighProfilePrefixes, decoderName)
  }

  fun decoderCanDirectSubmit(decoderName: String): Boolean {
    return isDecoderInList(directSubmitPrefixes, decoderName) && !isExynos4Device()
  }

  fun decoderNeedsSpsBitstreamRestrictions(decoderName: String): Boolean {
    return isDecoderInList(spsFixupBitstreamFixupDecoderPrefixes, decoderName)
  }

  fun decoderNeedsBaselineSpsHack(decoderName: String): Boolean {
    return isDecoderInList(baselineProfileHackPrefixes, decoderName)
  }

  fun getDecoderOptimalSlicesPerFrame(decoderName: String): Byte {
    return if (isDecoderInList(useFourSlicesPrefixes, decoderName)) 4 else 1
  }

  fun decoderSupportsRefFrameInvalidationAvc(decoderName: String, videoHeight: Int): Boolean {
    if (videoHeight > 720 && isLowEndSnapdragon) {
      return false
    }
    if (Build.DEVICE.equals("b3", ignoreCase = true) || Build.DEVICE.equals(
        "b5",
        ignoreCase = true
      )
    ) {
      return false
    }
    return isDecoderInList(refFrameInvalidationAvcPrefixes, decoderName)
  }

  fun decoderSupportsRefFrameInvalidationHevc(decoderInfo: MediaCodecInfo): Boolean {
    if (decoderSupportsAndroidRLowLatency(decoderInfo, "video/hevc") ||
      decoderSupportsKnownVendorLowLatencyOption(decoderInfo.name)
    ) {
      Timber.i("Enabling HEVC RFI based on low latency option support")
      return true
    }
    return isDecoderInList(refFrameInvalidationHevcPrefixes, decoderInfo.name)
  }

  fun decoderSupportsRefFrameInvalidationAv1(decoderInfo: MediaCodecInfo): Boolean {
    if (decoderSupportsAndroidRLowLatency(decoderInfo, "video/av01") ||
      decoderSupportsKnownVendorLowLatencyOption(decoderInfo.name)
    ) {
      Timber.i("Enabling AV1 RFI based on low latency option support")
      return true
    }
    return false
  }

  fun decoderIsWhitelistedForHevc(decoderInfo: MediaCodecInfo): Boolean {
    if (decoderInfo.name.contains("sw")) {
      Timber.i("Disallowing HEVC on software decoder: " + decoderInfo.name)
      return false
    } else if (!decoderInfo.isHardwareAccelerated || decoderInfo.isSoftwareOnly) {
      Timber.i("Disallowing HEVC on software decoder: " + decoderInfo.name)
      return false
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      Timber.i("Media performance class: " + Build.VERSION.MEDIA_PERFORMANCE_CLASS)
      if (Build.VERSION.MEDIA_PERFORMANCE_CLASS >= Build.VERSION_CODES.S) {
        Timber.i("Allowing HEVC based on media performance class")
        return true
      }
    }

    if (decoderSupportsAndroidRLowLatency(decoderInfo, "video/hevc")) {
      Timber.i("Allowing HEVC based on FEATURE_LowLatency support")
      return true
    }

    return isDecoderInList(whitelistedHevcDecoders, decoderInfo.name)
  }

  fun isDecoderWhitelistedForAv1(decoderInfo: MediaCodecInfo): Boolean {
    if (decoderInfo.name.contains("sw")) {
      Timber.i("Disallowing AV1 on software decoder: " + decoderInfo.name)
      return false
    } else if (!decoderInfo.isHardwareAccelerated || decoderInfo.isSoftwareOnly) {
      Timber.i("Disallowing AV1 on software decoder: " + decoderInfo.name)
      return false
    }
    return false
  }

  @SuppressLint("NewApi")
  private fun getMediaCodecList(): LinkedList<MediaCodecInfo> {
    val infoList = LinkedList<MediaCodecInfo>()
    val mcl = MediaCodecList(MediaCodecList.REGULAR_CODECS)
    Collections.addAll(infoList, *mcl.codecInfos)
    return infoList
  }

  fun dumpDecoders(): String {
    var str = ""
    for (codecInfo in getMediaCodecList()) {
      if (codecInfo.isEncoder) {
        continue
      }
      str += "Decoder: " + codecInfo.name + "\n"
      for (type in codecInfo.supportedTypes) {
        str += "\t" + type + "\n"
        val caps = codecInfo.getCapabilitiesForType(type)
        for (profile in caps.profileLevels) {
          str += "\t\t" + profile.profile + " " + profile.level + "\n"
        }
      }
    }
    return str
  }

  private fun findPreferredDecoder(): MediaCodecInfo? {
    if (!initialized) {
      throw IllegalStateException("MediaCodecHelper must be initialized before use")
    }
    for (preferredDecoder in preferredDecoders) {
      for (codecInfo in getMediaCodecList()) {
        if (codecInfo.isEncoder) {
          continue
        }
        if (preferredDecoder.equals(codecInfo.name, ignoreCase = true)) {
          Timber.i("Preferred decoder choice is " + codecInfo.name)
          return codecInfo
        }
      }
    }
    return null
  }

  private fun isCodecBlacklisted(codecInfo: MediaCodecInfo): Boolean {
    if (!SHOULD_BYPASS_SOFTWARE_BLOCK && codecInfo.isSoftwareOnly) {
      Timber.i("Skipping software-only decoder: " + codecInfo.name)
      return true
    }
    if (isDecoderInList(blacklistedDecoderPrefixes, codecInfo.name)) {
      Timber.i("Skipping blacklisted decoder: " + codecInfo.name)
      return true
    }
    return false
  }

  fun findFirstDecoder(mimeType: String): MediaCodecInfo? {
    for (codecInfo in getMediaCodecList()) {
      if (codecInfo.isEncoder) {
        continue
      }
      if (codecInfo.isAlias) {
        continue
      }
      for (mime in codecInfo.supportedTypes) {
        if (mime.equals(mimeType, ignoreCase = true)) {
          if (isCodecBlacklisted(codecInfo)) {
            continue
          }
          Timber.i("First decoder choice is " + codecInfo.name)
          return codecInfo
        }
      }
    }
    return null
  }

  fun findProbableSafeDecoder(mimeType: String, requiredProfile: Int): MediaCodecInfo? {
    var info = findPreferredDecoder()
    if (info != null) {
      return info
    }
    return try {
      findKnownSafeDecoder(mimeType, requiredProfile)
    } catch (e: Exception) {
      findFirstDecoder(mimeType)
    }
  }

  private fun findKnownSafeDecoder(mimeType: String, requiredProfile: Int): MediaCodecInfo? {
    for (i in 0..1) {
      for (codecInfo in getMediaCodecList()) {
        if (codecInfo.isEncoder) {
          continue
        }
        if (codecInfo.isAlias) {
          continue
        }
        for (mime in codecInfo.supportedTypes) {
          if (mime.equals(mimeType, ignoreCase = true)) {
            Timber.i("Examining decoder capabilities of " + codecInfo.name + " (round " + (i + 1) + ")")
            if (isCodecBlacklisted(codecInfo)) {
              continue
            }
            val caps = codecInfo.getCapabilitiesForType(mime)
            if (i == 0 && !decoderSupportsAndroidRLowLatency(codecInfo, mime)) {
              Timber.i("Skipping decoder that lacks FEATURE_LowLatency for round 1")
              continue
            }
            if (requiredProfile != -1) {
              for (profile in caps.profileLevels) {
                if (profile.profile == requiredProfile) {
                  Timber.i("Decoder " + codecInfo.name + " supports required profile")
                  return codecInfo
                }
              }
              Timber.i("Decoder " + codecInfo.name + " does NOT support required profile")
            } else {
              return codecInfo
            }
          }
        }
      }
    }
    return null
  }

  fun readCpuinfo(): String {
    return BufferedReader(FileReader(File("/proc/cpuinfo"))).use { br ->
      val cpuInfo = StringBuilder()
      var ch = br.read()
      while (ch != -1) {
        cpuInfo.append(ch.toChar())
        ch = br.read()
      }
      cpuInfo.toString()
    }
  }

  private fun stringContainsIgnoreCase(string: String, substring: String): Boolean {
    return string.lowercase(Locale.ENGLISH).contains(substring.lowercase(Locale.ENGLISH))
  }

  fun isExynos4Device(): Boolean {
    return try {
      val cpuInfo = readCpuinfo()
      if (stringContainsIgnoreCase(cpuInfo, "SMDK4")) {
        Timber.i("Found SMDK4 in /proc/cpuinfo")
        return true
      }
      if (stringContainsIgnoreCase(cpuInfo, "Exynos 4")) {
        Timber.i("Found Exynos 4 in /proc/cpuinfo")
        return true
      }
      false
    } catch (e: Exception) {
      e.printStackTrace()
      try {
        val systemDir = File("/sys/devices/system")
        val files = systemDir.listFiles()
        if (files != null) {
          for (f in files) {
            if (stringContainsIgnoreCase(f.name, "exynos4")) {
              Timber.i("Found exynos4 in /sys/devices/system")
              return true
            }
          }
        }
      } catch (e2: Exception) {
        e2.printStackTrace()
      }
      false
    }
  }
}

