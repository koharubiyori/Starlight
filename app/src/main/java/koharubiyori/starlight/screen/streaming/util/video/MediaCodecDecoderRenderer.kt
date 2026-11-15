package koharubiyori.starlight.screen.streaming.util.video

import android.app.Activity
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodec.CodecException
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.VideoCapabilities
import android.media.MediaCodecInfo.VideoCapabilities.PerformancePoint
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.view.Choreographer
import android.view.SurfaceHolder
import koharubiyori.starlight.BuildConfig
import koharubiyori.starlight.R
import koharubiyori.starlight.nvstream.av.video.VideoDecoderRenderer
import koharubiyori.starlight.nvstream.jni.MoonBridge
import koharubiyori.starlight.store.proto.FormatOption
import koharubiyori.starlight.store.proto.FramePacing
import koharubiyori.starlight.store.proto.SettingsStoreData
import koharubiyori.starlight.screen.settings.bitrate
import org.jcodec.codecs.h264.H264Utils
import org.jcodec.codecs.h264.io.model.SeqParameterSet
import org.jcodec.codecs.h264.io.model.VUIParameters
import org.jcodec.codecs.h264.io.model.VUIParameters.BitstreamRestriction
import timber.log.Timber
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.Volatile
import kotlin.math.max
import kotlin.math.min

class MediaCodecDecoderRenderer(
  activity: Activity, prefs: SettingsStoreData,
  crashListener: CrashListener, consecutiveCrashCount: Int,
  meteredData: Boolean, requestedHdr: Boolean,
  glRenderer: String?, perfListener: PerfOverlayListener
) : VideoDecoderRenderer(), Choreographer.FrameCallback {
  interface CrashListener {
    fun notifyCrash(e: Exception)
  }

  interface PerfOverlayListener {
    fun onPerfUpdate(performanceInfo: PerformanceInfo)
  }

  // Used on versions < 5.0

  private var avcDecoder: MediaCodecInfo?
  private var hevcDecoder: MediaCodecInfo?
  private var av1Decoder: MediaCodecInfo?

  private val vpsBuffers = ArrayList<ByteArray>()
  private val spsBuffers = ArrayList<ByteArray>()
  private val ppsBuffers = ArrayList<ByteArray>()
  private var submittedCsd = false
  private var currentHdrMetadata: ByteArray? = null

  private var nextInputBufferIndex = -1
  private var nextInputBuffer: ByteBuffer? = null

  private val context: Context
  private val activity: Activity
  private var videoDecoder: MediaCodec? = null
  private var rendererThread: Thread? = null
  private var needsSpsBitstreamFixup = false
  private var isExynos4 = false
  private var adaptivePlayback = false
  private var directSubmit = false
  private var fusedIdrFrame = false
  private var constrainedHighProfile = false
  private var refFrameInvalidationAvc = false
  private var refFrameInvalidationHevc = false
  private var refFrameInvalidationAv1 = false
  private val optimalSlicesPerFrame: Byte
  private var refFrameInvalidationActive = false
  private var initialWidth = 0
  private var initialHeight = 0
  var activeVideoFormat: Int = 0
    private set
  private var renderTarget: SurfaceHolder? = null

  @Volatile
  private var stopping = false
  private val crashListener: CrashListener
  private var reportedCrash = false
  private val consecutiveCrashCount: Int
  private val glRenderer: String?
  private var foreground = true
  private val perfListener: PerfOverlayListener

  private val codecRecoveryType = AtomicInteger(CR_RECOVERY_TYPE_NONE)
  private val codecRecoveryMonitor = Any()

  private var codecRecoveryThreadQuiescedFlags = 0
  private var codecRecoveryAttempts = 0

  private var inputFormat: MediaFormat? = null
  private var outputFormat: MediaFormat? = null
  private var configuredFormat: MediaFormat? = null

  private var needsBaselineSpsHack = false
  private var savedSps: SeqParameterSet? = null

  private var initialException: RendererException? = null
  private var initialExceptionTimestamp: Long = 0
  private val activeWindowVideoStats: VideoStats
  private val lastWindowVideoStats: VideoStats
  private val globalVideoStats: VideoStats

  private var lastTimestampUs: Long = 0
  private var lastFrameNumber = 0
  private var refreshRate = 0
  private val prefs: SettingsStoreData

  private val outputBufferQueue = LinkedBlockingQueue<Int>()
  private var lastRenderedFrameTimeNanos: Long = 0
  private var choreographerHandlerThread: HandlerThread? = null
  private var choreographerHandler: Handler? = null

  private var numSpsIn = 0
  private var numPpsIn = 0
  private var numVpsIn = 0
  private var numFramesIn = 0
  private var numFramesOut = 0

  private fun findAvcDecoder(): MediaCodecInfo? {
    var decoder = MediaCodecHelper.findProbableSafeDecoder(
      "video/avc",
      MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
    )
    if (decoder == null) {
      decoder = MediaCodecHelper.findFirstDecoder("video/avc")
    }
    return decoder
  }

  private fun decoderCanMeetPerformancePoint(
    caps: VideoCapabilities,
    prefs: SettingsStoreData
  ): Boolean {
    val targetPerfPoint = PerformancePoint(prefs.getWidth(), prefs.getHeight(), prefs.getFps())
    val perfPoints = caps.getSupportedPerformancePoints()
    if (perfPoints != null) {
      for (perfPoint in perfPoints) {
        // If we find a performance point that covers our target, we're good to go
        if (perfPoint.covers(targetPerfPoint)) {
          return true
        }
      }

      // We had performance point data but none met the specified streaming settings
      return false
    }

    // Fall-through to try the Android M API if there's no performance point data

    try {
      // We'll ask the decoder what it can do for us at this resolution and see if our
      // requested frame rate falls below or inside the range of achievable frame rates.
      val fpsRange = caps.getAchievableFrameRatesFor(prefs.getWidth(), prefs.getHeight())
      if (fpsRange != null) {
        return prefs.getFps() <= fpsRange.getUpper()!!
      }

      // Fall-through to try the Android L API if there's no performance point data
    } catch (e: IllegalArgumentException) {
      // Video size not supported at any frame rate
      return false
    }

    // As a last resort, we will use areSizeAndRateSupported() which is explicitly NOT a
    // performance metric, but it can work at least for the purpose of determining if
    // the codec is going to die when given a stream with the specified settings.
    return caps.areSizeAndRateSupported(
      prefs.getWidth(),
      prefs.getHeight(),
      prefs.getFps().toDouble()
    )
  }

  private fun decoderCanMeetPerformancePointWithHevcAndNotAvc(
    hevcDecoderInfo: MediaCodecInfo,
    avcDecoderInfo: MediaCodecInfo,
    prefs: SettingsStoreData
  ): Boolean {
    val avcCaps = avcDecoderInfo.getCapabilitiesForType("video/avc").getVideoCapabilities()
    val hevcCaps = hevcDecoderInfo.getCapabilitiesForType("video/hevc").getVideoCapabilities()

    return !decoderCanMeetPerformancePoint(avcCaps!!, prefs) && decoderCanMeetPerformancePoint(
      hevcCaps!!,
      prefs
    )
  }

  private fun decoderCanMeetPerformancePointWithAv1AndNotHevc(
    av1DecoderInfo: MediaCodecInfo,
    hevcDecoderInfo: MediaCodecInfo,
    prefs: SettingsStoreData
  ): Boolean {
    val av1Caps = av1DecoderInfo.getCapabilitiesForType("video/av01").getVideoCapabilities()
    val hevcCaps = hevcDecoderInfo.getCapabilitiesForType("video/hevc").getVideoCapabilities()

    return !decoderCanMeetPerformancePoint(hevcCaps!!, prefs) && decoderCanMeetPerformancePoint(
      av1Caps!!,
      prefs
    )
  }

  private fun decoderCanMeetPerformancePointWithAv1AndNotAvc(
    av1DecoderInfo: MediaCodecInfo,
    avcDecoderInfo: MediaCodecInfo,
    prefs: SettingsStoreData
  ): Boolean {
    val avcCaps = avcDecoderInfo.getCapabilitiesForType("video/avc").getVideoCapabilities()
    val av1Caps = av1DecoderInfo.getCapabilitiesForType("video/av01").getVideoCapabilities()

    return !decoderCanMeetPerformancePoint(avcCaps!!, prefs) && decoderCanMeetPerformancePoint(
      av1Caps!!,
      prefs
    )
  }

  private fun findHevcDecoder(
    prefs: SettingsStoreData,
    meteredNetwork: Boolean,
    requestedHdr: Boolean
  ): MediaCodecInfo? {
    // Don't return anything if H.264 is forced
    if (prefs.getVideoFormat() == FormatOption.H264) {
      return null
    }

    // We don't try the first HEVC decoder. We'd rather fall back to hardware accelerated AVC instead
    //
    // We need HEVC Main profile, so we could pass that constant to findProbableSafeDecoder, however
    // some decoders (at least Qualcomm's Snapdragon 805) don't properly report support
    // for even required levels of HEVC.
    val hevcDecoderInfo = MediaCodecHelper.findProbableSafeDecoder("video/hevc", -1)
    if (hevcDecoderInfo != null) {
      if (!MediaCodecHelper.decoderIsWhitelistedForHevc(hevcDecoderInfo)) {
        Timber.i("Found HEVC decoder, but it's not whitelisted - " + hevcDecoderInfo.getName())

        // Force HEVC enabled if the user asked for it
        if (prefs.getVideoFormat() == FormatOption.HEVC) {
          Timber.i("Forcing HEVC enabled despite non-whitelisted decoder")
        } else if (requestedHdr) {
          Timber.i("Forcing HEVC enabled for HDR streaming")
        } else if (prefs.getWidth() > 4096 || prefs.getHeight() > 4096) {
          Timber.i("Forcing HEVC enabled for over 4K streaming")
        } else if (avcDecoder != null && decoderCanMeetPerformancePointWithHevcAndNotAvc(
            hevcDecoderInfo,
            avcDecoder!!,
            prefs
          )
        ) {
          Timber.i("Using non-whitelisted HEVC decoder to meet performance point")
        } else {
          return null
        }
      }
    }

    return hevcDecoderInfo
  }

  private fun findAv1Decoder(prefs: SettingsStoreData): MediaCodecInfo? {
    // For now, don't use AV1 unless explicitly requested
    if (prefs.getVideoFormat() != FormatOption.AV1) {
      return null
    }

    val decoderInfo = MediaCodecHelper.findProbableSafeDecoder("video/av01", -1)
    if (decoderInfo != null) {
      if (!MediaCodecHelper.isDecoderWhitelistedForAv1(decoderInfo)) {
        Timber.i("Found AV1 decoder, but it's not whitelisted - " + decoderInfo.getName())

        // Force HEVC enabled if the user asked for it
        if (prefs.getVideoFormat() == FormatOption.AV1) {
          Timber.i("Forcing AV1 enabled despite non-whitelisted decoder")
        } else if (hevcDecoder != null && decoderCanMeetPerformancePointWithAv1AndNotHevc(
            decoderInfo,
            hevcDecoder!!,
            prefs
          )
        ) {
          Timber.i("Using non-whitelisted AV1 decoder to meet performance point")
        } else if (hevcDecoder == null && decoderCanMeetPerformancePointWithAv1AndNotAvc(
            decoderInfo,
            avcDecoder!!,
            prefs
          )
        ) {
          Timber.i("Using non-whitelisted AV1 decoder to meet performance point")
        } else {
          return null
        }
      }
    }

    return decoderInfo
  }

  fun setRenderTarget(renderTarget: SurfaceHolder) {
    this.renderTarget = renderTarget
  }

  init {
    //dumpDecoders();

    this.context = activity
    this.activity = activity
    this.prefs = prefs
    this.crashListener = crashListener
    this.consecutiveCrashCount = consecutiveCrashCount
    this.glRenderer = glRenderer
    this.perfListener = perfListener

    this.activeWindowVideoStats = VideoStats()
    this.lastWindowVideoStats = VideoStats()
    this.globalVideoStats = VideoStats()

    avcDecoder = findAvcDecoder()
    if (avcDecoder != null) {
      Timber.i("Selected AVC decoder: " + avcDecoder!!.getName())
    } else {
      Timber.w("No AVC decoder found")
    }

    hevcDecoder = findHevcDecoder(prefs, meteredData, requestedHdr)
    if (hevcDecoder != null) {
      Timber.i("Selected HEVC decoder: " + hevcDecoder!!.getName())
    } else {
      Timber.i("No HEVC decoder found")
    }

    av1Decoder = findAv1Decoder(prefs)
    if (av1Decoder != null) {
      Timber.i("Selected AV1 decoder: " + av1Decoder!!.getName())
    } else {
      Timber.i("No AV1 decoder found")
    }

    // Set attributes that are queried in getCapabilities(). This must be done here
    // because getCapabilities() may be called before setup() in current versions of the common
    // library. The limitation of this is that we don't know whether we're using HEVC or AVC.
    var avcOptimalSlicesPerFrame = 0
    var hevcOptimalSlicesPerFrame = 0
    if (avcDecoder != null) {
      directSubmit = MediaCodecHelper.decoderCanDirectSubmit(avcDecoder!!.getName())
      refFrameInvalidationAvc = MediaCodecHelper.decoderSupportsRefFrameInvalidationAvc(
        avcDecoder!!.getName(),
        prefs.getHeight()
      )
      avcOptimalSlicesPerFrame =
        MediaCodecHelper.getDecoderOptimalSlicesPerFrame(avcDecoder!!.getName()).toInt()

      if (directSubmit) {
        Timber.i("Decoder " + avcDecoder!!.getName() + " will use direct submit")
      }
      if (refFrameInvalidationAvc) {
        Timber.i("Decoder " + avcDecoder!!.getName() + " will use reference frame invalidation for AVC")
      }
      Timber.i("Decoder " + avcDecoder!!.getName() + " wants " + avcOptimalSlicesPerFrame + " slices per frame")
    }

    if (hevcDecoder != null) {
      refFrameInvalidationHevc =
        MediaCodecHelper.decoderSupportsRefFrameInvalidationHevc(hevcDecoder!!)
      hevcOptimalSlicesPerFrame =
        MediaCodecHelper.getDecoderOptimalSlicesPerFrame(hevcDecoder!!.getName()).toInt()

      if (refFrameInvalidationHevc) {
        Timber.i("Decoder " + hevcDecoder!!.getName() + " will use reference frame invalidation for HEVC")
      }

      Timber.i("Decoder " + hevcDecoder!!.getName() + " wants " + hevcOptimalSlicesPerFrame + " slices per frame")
    }

    if (av1Decoder != null) {
      refFrameInvalidationAv1 = MediaCodecHelper.decoderSupportsRefFrameInvalidationAv1(av1Decoder!!)

      if (refFrameInvalidationAv1) {
        Timber.i("Decoder " + av1Decoder!!.getName() + " will use reference frame invalidation for AV1")
      }
    }

    // Use the larger of the two slices per frame preferences
    optimalSlicesPerFrame = max(avcOptimalSlicesPerFrame, hevcOptimalSlicesPerFrame).toByte()
    Timber.i("Requesting " + optimalSlicesPerFrame + " slices per frame")

    if (consecutiveCrashCount % 2 == 1) {
      refFrameInvalidationHevc = false
      refFrameInvalidationAvc = refFrameInvalidationHevc
      Timber.w("Disabling RFI due to previous crash")
    }
  }

  val isHevcSupported: Boolean
    get() = hevcDecoder != null

  val isAvcSupported: Boolean
    get() = avcDecoder != null

  val isHevcMain10Hdr10Supported: Boolean
    get() {
      if (hevcDecoder == null) {
        return false
      }

      for (profileLevel in hevcDecoder!!.getCapabilitiesForType("video/hevc").profileLevels) {
        if (profileLevel.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10) {
          Timber.i("HEVC decoder " + hevcDecoder!!.getName() + " supports HEVC Main10 HDR10")
          return true
        }
      }

      return false
    }

  val isAv1Supported: Boolean
    get() = av1Decoder != null

  val isAv1Main10Supported: Boolean
    get() {
      if (av1Decoder == null) {
        return false
      }

      for (profileLevel in av1Decoder!!.getCapabilitiesForType("video/av01").profileLevels) {
        if (profileLevel.profile == MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10) {
          Timber.i("AV1 decoder " + av1Decoder!!.getName() + " supports AV1 Main 10 HDR10")
          return true
        }
      }

      return false
    }

  val preferredColorSpace: Int
    get() {
      // Default to Rec 709 which is probably better supported on modern devices.
      //
      // We are sticking to Rec 601 on older devices unless the device has an HEVC decoder
      // to avoid possible regressions (and they are < 5% of installed devices). If we have
      // an HEVC decoder, we will use Rec 709 (even for H.264) since we can't choose a
      // colorspace by codec (and it's probably safe to say a SoC with HEVC decoding is
      // plenty modern enough to handle H.264 VUI colorspace info).
      if (hevcDecoder != null || av1Decoder != null) {
        return MoonBridge.COLORSPACE_REC_709
      } else {
        return MoonBridge.COLORSPACE_REC_601
      }
    }

  val preferredColorRange: Int
    get() {
      if (prefs.fullRange) {
        return MoonBridge.COLOR_RANGE_FULL
      } else {
        return MoonBridge.COLOR_RANGE_LIMITED
      }
    }

  fun notifyVideoForeground() {
    foreground = true
  }

  fun notifyVideoBackground() {
    foreground = false
  }

  private fun createBaseMediaFormat(mimeType: String): MediaFormat {
    val videoFormat = MediaFormat.createVideoFormat(mimeType, initialWidth, initialHeight)

    // Avoid setting KEY_FRAME_RATE on Lollipop and earlier to reduce compatibility risk
    videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, refreshRate)

    // Populate keys for adaptive playback
    if (adaptivePlayback) {
      videoFormat.setInteger(MediaFormat.KEY_MAX_WIDTH, initialWidth)
      videoFormat.setInteger(MediaFormat.KEY_MAX_HEIGHT, initialHeight)
    }

    // Android 7.0 adds color options to the MediaFormat
    videoFormat.setInteger(
      MediaFormat.KEY_COLOR_RANGE,
      if (this.preferredColorRange == MoonBridge.COLOR_RANGE_FULL) MediaFormat.COLOR_RANGE_FULL else MediaFormat.COLOR_RANGE_LIMITED
    )

    // If the stream is HDR-capable, the decoder will detect transitions in color standards
    // rather than us hardcoding them into the MediaFormat.
    if ((this.activeVideoFormat and MoonBridge.VIDEO_FORMAT_MASK_10BIT) == 0) {
      // Set color format keys when not in HDR mode, since we know they won't change
      videoFormat.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
      when (this.preferredColorSpace) {
        MoonBridge.COLORSPACE_REC_601 -> videoFormat.setInteger(
          MediaFormat.KEY_COLOR_STANDARD,
          MediaFormat.COLOR_STANDARD_BT601_NTSC
        )

        MoonBridge.COLORSPACE_REC_709 -> videoFormat.setInteger(
          MediaFormat.KEY_COLOR_STANDARD,
          MediaFormat.COLOR_STANDARD_BT709
        )

        MoonBridge.COLORSPACE_REC_2020 -> videoFormat.setInteger(
          MediaFormat.KEY_COLOR_STANDARD,
          MediaFormat.COLOR_STANDARD_BT2020
        )
      }
    }

    return videoFormat
  }

  private fun configureAndStartDecoder(format: MediaFormat) {
    // Set HDR metadata if present
    if (currentHdrMetadata != null) {
      val hdrStaticInfo = ByteBuffer.allocate(25).order(ByteOrder.LITTLE_ENDIAN)
      val hdrMetadata = ByteBuffer.wrap(currentHdrMetadata).order(ByteOrder.LITTLE_ENDIAN)

      // Create a HDMI Dynamic Range and Mastering InfoFrame as defined by CTA-861.3
      hdrStaticInfo.put(0.toByte()) // Metadata type
      hdrStaticInfo.putShort(hdrMetadata.getShort()) // RX
      hdrStaticInfo.putShort(hdrMetadata.getShort()) // RY
      hdrStaticInfo.putShort(hdrMetadata.getShort()) // GX
      hdrStaticInfo.putShort(hdrMetadata.getShort()) // GY
      hdrStaticInfo.putShort(hdrMetadata.getShort()) // BX
      hdrStaticInfo.putShort(hdrMetadata.getShort()) // BY
      hdrStaticInfo.putShort(hdrMetadata.getShort()) // White X
      hdrStaticInfo.putShort(hdrMetadata.getShort()) // White Y
      hdrStaticInfo.putShort(hdrMetadata.getShort()) // Max mastering luminance
      hdrStaticInfo.putShort(hdrMetadata.getShort()) // Min mastering luminance
      hdrStaticInfo.putShort(hdrMetadata.getShort()) // Max content luminance
      hdrStaticInfo.putShort(hdrMetadata.getShort()) // Max frame average luminance

      hdrStaticInfo.rewind()
      format.setByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO, hdrStaticInfo)
    } else
      format.removeKey(MediaFormat.KEY_HDR_STATIC_INFO)

    Timber.i("Configuring with format: " + format)

    videoDecoder!!.configure(format, renderTarget!!.getSurface(), null, 0)

    configuredFormat = format

    // After reconfiguration, we must resubmit CSD buffers
    submittedCsd = false
    vpsBuffers.clear()
    spsBuffers.clear()
    ppsBuffers.clear()

    // This will contain the actual accepted input format attributes
    inputFormat = videoDecoder!!.inputFormat
    Timber.i("Input format: $inputFormat")

    videoDecoder!!.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT)

    // Start the decoder
    videoDecoder!!.start()
  }

  private fun tryConfigureDecoder(
    selectedDecoderInfo: MediaCodecInfo,
    format: MediaFormat,
    throwOnCodecError: Boolean
  ): Boolean {
    var configured = false
    try {
      videoDecoder = MediaCodec.createByCodecName(selectedDecoderInfo.getName())
      configureAndStartDecoder(format)
      Timber.i(
        "Using codec " + selectedDecoderInfo.getName() + " for hardware decoding " + format.getString(
          MediaFormat.KEY_MIME
        )
      )
      configured = true
    } catch (e: IllegalArgumentException) {
      e.printStackTrace()
      if (throwOnCodecError) {
        throw e
      }
    } catch (e: IllegalStateException) {
      e.printStackTrace()
      if (throwOnCodecError) {
        throw e
      }
    } catch (e: IOException) {
      e.printStackTrace()
      if (throwOnCodecError) {
        throw RuntimeException(e)
      }
    } finally {
      if (!configured && videoDecoder != null) {
        videoDecoder!!.release()
        videoDecoder = null
      }
    }
    return configured
  }

  fun initializeDecoder(throwOnCodecError: Boolean): Int {
    val mimeType: String?
    val selectedDecoderInfo: MediaCodecInfo?

    if ((this.activeVideoFormat and MoonBridge.VIDEO_FORMAT_MASK_H264) != 0) {
      mimeType = "video/avc"
      selectedDecoderInfo = avcDecoder

      if (avcDecoder == null) {
        Timber.i("No available AVC decoder!")
        return -1
      }

      if (initialWidth > 4096 || initialHeight > 4096) {
        Timber.i("> 4K streaming only supported on HEVC")
        return -1
      }

      // These fixups only apply to H264 decoders
      needsSpsBitstreamFixup =
        MediaCodecHelper.decoderNeedsSpsBitstreamRestrictions(selectedDecoderInfo!!.getName())
      needsBaselineSpsHack =
        MediaCodecHelper.decoderNeedsBaselineSpsHack(selectedDecoderInfo.getName())
      constrainedHighProfile =
        MediaCodecHelper.decoderNeedsConstrainedHighProfile(selectedDecoderInfo.getName())
      isExynos4 = MediaCodecHelper.isExynos4Device()
      if (needsSpsBitstreamFixup) {
        Timber.i("Decoder " + selectedDecoderInfo.getName() + " needs SPS bitstream restrictions fixup")
      }
      if (needsBaselineSpsHack) {
        Timber.i("Decoder " + selectedDecoderInfo.getName() + " needs baseline SPS hack")
      }
      if (constrainedHighProfile) {
        Timber.i("Decoder " + selectedDecoderInfo.getName() + " needs constrained high profile")
      }
      if (isExynos4) {
        Timber.i("Decoder " + selectedDecoderInfo.getName() + " is on Exynos 4")
      }

      refFrameInvalidationActive = refFrameInvalidationAvc
    } else if ((this.activeVideoFormat and MoonBridge.VIDEO_FORMAT_MASK_H265) != 0) {
      mimeType = "video/hevc"
      selectedDecoderInfo = hevcDecoder

      if (hevcDecoder == null) {
        Timber.i("No available HEVC decoder!")
        return -2
      }

      refFrameInvalidationActive = refFrameInvalidationHevc
    } else if ((this.activeVideoFormat and MoonBridge.VIDEO_FORMAT_MASK_AV1) != 0) {
      mimeType = "video/av01"
      selectedDecoderInfo = av1Decoder

      if (av1Decoder == null) {
        Timber.i("No available AV1 decoder!")
        return -2
      }

      refFrameInvalidationActive = refFrameInvalidationAv1
    } else {
      // Unknown format
      Timber.i("Unknown format")
      return -3
    }

    adaptivePlayback =
      MediaCodecHelper.decoderSupportsAdaptivePlayback(selectedDecoderInfo!!, mimeType)
    fusedIdrFrame = MediaCodecHelper.decoderSupportsFusedIdrFrame(selectedDecoderInfo!!, mimeType)

    var tryNumber = 0
    while (true) {
      Timber.i("Decoder configuration try: " + tryNumber)

      val mediaFormat = createBaseMediaFormat(mimeType)

      // This will try low latency options until we find one that works (or we give up).
      val newFormat =
        MediaCodecHelper.setDecoderLowLatencyOptions(mediaFormat, selectedDecoderInfo!!, tryNumber)

      // Throw the underlying codec exception on the last attempt if the caller requested it
      if (tryConfigureDecoder(
          selectedDecoderInfo!!,
          mediaFormat,
          !newFormat && throwOnCodecError
        )
      ) {
        // Success!
        break
      }

      if (!newFormat) {
        // We couldn't even configure a decoder without any low latency options
        return -5
      }
      tryNumber++
    }

    if (USE_FRAME_RENDER_TIME) {
      videoDecoder!!.setOnFrameRenderedListener({ mediaCodec, presentationTimeUs, renderTimeNanos ->
        val delta = (renderTimeNanos / 1000000L) - (presentationTimeUs / 1000)
        if (delta in 0..<1000) {
          if (USE_FRAME_RENDER_TIME) {
            activeWindowVideoStats.totalTimeMs += delta
          }
        }
      }, null)
    }

    return 0
  }

  override fun setup(format: Int, width: Int, height: Int, redrawRate: Int): Int {
    this.initialWidth = width
    this.initialHeight = height
    this.activeVideoFormat = format
    this.refreshRate = redrawRate

    return initializeDecoder(false)
  }

  // All threads that interact with the MediaCodec instance must call this function regularly!
  private fun doCodecRecoveryIfRequired(quiescenceFlag: Int): Boolean {
    // NB: We cannot check 'stopping' here because we could end up bailing in a partially
    // quiesced state that will cause the quiesced threads to never wake up.
    if (codecRecoveryType.get() == CR_RECOVERY_TYPE_NONE) {
      // Common case
      return false
    }

    // We need some sort of recovery, so quiesce all threads before starting that
    synchronized(codecRecoveryMonitor) {
      if (choreographerHandlerThread == null) {
        // If we have no choreographer thread, we can just mark that as quiesced right now.
        codecRecoveryThreadQuiescedFlags = codecRecoveryThreadQuiescedFlags or CR_FLAG_CHOREOGRAPHER
      }
      codecRecoveryThreadQuiescedFlags = codecRecoveryThreadQuiescedFlags or quiescenceFlag

      // This is the final thread to quiesce, so let's perform the codec recovery now.
      if (codecRecoveryThreadQuiescedFlags == CR_FLAG_ALL) {
        // Input and output buffers are invalidated by stop() and reset().
        nextInputBuffer = null
        nextInputBufferIndex = -1
        outputBufferQueue.clear()

        // If we just need a flush, do so now with all threads quiesced.
        if (codecRecoveryType.get() == CR_RECOVERY_TYPE_FLUSH) {
          Timber.w("Flushing decoder")
          try {
            videoDecoder!!.flush()
            codecRecoveryType.set(CR_RECOVERY_TYPE_NONE)
          } catch (e: IllegalStateException) {
            e.printStackTrace()

            // Something went wrong during the restart, let's use a bigger hammer
            // and try a reset instead.
            codecRecoveryType.set(CR_RECOVERY_TYPE_RESTART)
          }
        }

        // We don't count flushes as codec recovery attempts
        if (codecRecoveryType.get() != CR_RECOVERY_TYPE_NONE) {
          codecRecoveryAttempts++
          Timber.i("Codec recovery attempt: " + codecRecoveryAttempts)
        }

        // For "recoverable" exceptions, we can just stop, reconfigure, and restart.
        if (codecRecoveryType.get() == CR_RECOVERY_TYPE_RESTART) {
          Timber.w("Trying to restart decoder after CodecException")
          try {
            videoDecoder!!.stop()
            configureAndStartDecoder(configuredFormat!!)
            codecRecoveryType.set(CR_RECOVERY_TYPE_NONE)
          } catch (e: IllegalArgumentException) {
            e.printStackTrace()

            // Our Surface is probably invalid, so just stop
            stopping = true
            codecRecoveryType.set(CR_RECOVERY_TYPE_NONE)
          } catch (e: IllegalStateException) {
            e.printStackTrace()

            // Something went wrong during the restart, let's use a bigger hammer
            // and try a reset instead.
            codecRecoveryType.set(CR_RECOVERY_TYPE_RESET)
          }
        }

        // For "non-recoverable" exceptions on L+, we can call reset() to recover
        // without having to recreate the entire decoder again.
        if (codecRecoveryType.get() == CR_RECOVERY_TYPE_RESET) {
          Timber.w("Trying to reset decoder after CodecException")
          try {
            videoDecoder!!.reset()
            configureAndStartDecoder(configuredFormat!!)
            codecRecoveryType.set(CR_RECOVERY_TYPE_NONE)
          } catch (e: IllegalArgumentException) {
            e.printStackTrace()

            // Our Surface is probably invalid, so just stop
            stopping = true
            codecRecoveryType.set(CR_RECOVERY_TYPE_NONE)
          } catch (e: IllegalStateException) {
            e.printStackTrace()

            // Something went wrong during the reset, we'll have to resort to
            // releasing and recreating the decoder now.
          }
        }

        // If we _still_ haven't managed to recover, go for the nuclear option and just
        // throw away the old decoder and reinitialize a new one from scratch.
        if (codecRecoveryType.get() == CR_RECOVERY_TYPE_RESET) {
          Timber.w("Trying to recreate decoder after CodecException")
          videoDecoder!!.release()

          try {
            val err = initializeDecoder(true)
            check(err == 0) { "Decoder reset failed: " + err }
            codecRecoveryType.set(CR_RECOVERY_TYPE_NONE)
          } catch (e: IllegalArgumentException) {
            e.printStackTrace()

            // Our Surface is probably invalid, so just stop
            stopping = true
            codecRecoveryType.set(CR_RECOVERY_TYPE_NONE)
          } catch (e: IllegalStateException) {
            // If we failed to recover after all of these attempts, just crash
            if (!reportedCrash) {
              reportedCrash = true
              crashListener.notifyCrash(e)
            }
            throw RendererException(this, e)
          }
        }

        // Wake all quiesced threads and allow them to begin work again
        codecRecoveryThreadQuiescedFlags = 0
        (codecRecoveryMonitor as Object).notifyAll()
      } else {
        // If we haven't quiesced all threads yet, wait to be signalled after recovery.
        // The final thread to be quiesced will handle the codec recovery.
        while (codecRecoveryType.get() != CR_RECOVERY_TYPE_NONE) {
          try {
            Timber.i("Waiting to quiesce decoder threads: " + codecRecoveryThreadQuiescedFlags)
            (codecRecoveryMonitor as Object).wait(1000)
          } catch (e: InterruptedException) {
            e.printStackTrace()

            // InterruptedException clears the thread's interrupt status. Since we can't
            // handle that here, we will re-interrupt the thread to set the interrupt
            // status back to true.
            Thread.currentThread().interrupt()

            break
          }
        }
      }
    }

    return true
  }

  // Returns true if the exception is transient
  private fun handleDecoderException(e: IllegalStateException): Boolean {
    // Eat decoder exceptions if we're in the process of stopping
    if (stopping) {
      return false
    }

    if (e is CodecException) {
      val codecExc = e

      if (codecExc.isTransient()) {
        // We'll let transient exceptions go
        Timber.w(codecExc.getDiagnosticInfo())
        return true
      }

      Timber.i(codecExc.getDiagnosticInfo())

      // We can attempt a recovery or reset at this stage to try to start decoding again
      if (codecRecoveryAttempts < CR_MAX_TRIES) {
        // If the exception is non-recoverable or we already require a reset, perform a reset.
        // If we have no prior unrecoverable failure, we will try a restart instead.
        if (codecExc.isRecoverable()) {
          if (codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_NONE, CR_RECOVERY_TYPE_RESTART)) {
            Timber.i("Decoder requires restart for recoverable CodecException")
            e.printStackTrace()
          } else if (codecRecoveryType.compareAndSet(
              CR_RECOVERY_TYPE_FLUSH,
              CR_RECOVERY_TYPE_RESTART
            )
          ) {
            Timber.i("Decoder flush promoted to restart for recoverable CodecException")
            e.printStackTrace()
          } else check(!(codecRecoveryType.get() != CR_RECOVERY_TYPE_RESET && codecRecoveryType.get() != CR_RECOVERY_TYPE_RESTART)) { "Unexpected codec recovery type: " + codecRecoveryType.get() }
        } else if (!codecExc.isRecoverable()) {
          if (codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_NONE, CR_RECOVERY_TYPE_RESET)) {
            Timber.i("Decoder requires reset for non-recoverable CodecException")
            e.printStackTrace()
          } else if (codecRecoveryType.compareAndSet(
              CR_RECOVERY_TYPE_FLUSH,
              CR_RECOVERY_TYPE_RESET
            )
          ) {
            Timber.i("Decoder flush promoted to reset for non-recoverable CodecException")
            e.printStackTrace()
          } else if (codecRecoveryType.compareAndSet(
              CR_RECOVERY_TYPE_RESTART,
              CR_RECOVERY_TYPE_RESET
            )
          ) {
            Timber.i("Decoder restart promoted to reset for non-recoverable CodecException")
            e.printStackTrace()
          } else check(codecRecoveryType.get() == CR_RECOVERY_TYPE_RESET) { "Unexpected codec recovery type: " + codecRecoveryType.get() }
        }

        // The recovery will take place when all threads reach doCodecRecoveryIfRequired().
        return false
      }
    } else {
      // IllegalStateException was primarily used prior to the introduction of CodecException.
      // Recovery from this requires a full decoder reset.
      //
      // NB: CodecException is an IllegalStateException, so we must check for it first.
      if (codecRecoveryAttempts < CR_MAX_TRIES) {
        if (codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_NONE, CR_RECOVERY_TYPE_RESET)) {
          Timber.i("Decoder requires reset for IllegalStateException")
          e.printStackTrace()
        } else if (codecRecoveryType.compareAndSet(
            CR_RECOVERY_TYPE_FLUSH,
            CR_RECOVERY_TYPE_RESET
          )
        ) {
          Timber.i("Decoder flush promoted to reset for IllegalStateException")
          e.printStackTrace()
        } else if (codecRecoveryType.compareAndSet(
            CR_RECOVERY_TYPE_RESTART,
            CR_RECOVERY_TYPE_RESET
          )
        ) {
          Timber.i("Decoder restart promoted to reset for IllegalStateException")
          e.printStackTrace()
        } else check(codecRecoveryType.get() == CR_RECOVERY_TYPE_RESET) { "Unexpected codec recovery type: " + codecRecoveryType.get() }

        return false
      }
    }

    // Only throw if we're not in the middle of codec recovery
    if (codecRecoveryType.get() == CR_RECOVERY_TYPE_NONE) {
      //
      // There seems to be a race condition with decoder/surface teardown causing some
      // decoders to to throw IllegalStateExceptions even before 'stopping' is set.
      // To workaround this while allowing real exceptions to propagate, we will eat the
      // first exception. If we are still receiving exceptions 3 seconds later, we will
      // throw the original exception again.
      //
      if (initialException != null) {
        // This isn't the first time we've had an exception processing video
        if (SystemClock.uptimeMillis() - initialExceptionTimestamp >= EXCEPTION_REPORT_DELAY_MS) {
          // It's been over 3 seconds and we're still getting exceptions. Throw the original now.
          if (!reportedCrash) {
            reportedCrash = true
            crashListener.notifyCrash(initialException!!)
          }
          throw initialException!!
        }
      } else {
        // This is the first exception we've hit
        initialException = RendererException(this, e)
        initialExceptionTimestamp = SystemClock.uptimeMillis()
      }
    }

    // Not transient
    return false
  }

  override fun doFrame(frameTimeNanos: Long) {
    // Do nothing if we're stopping
    var frameTimeNanos = frameTimeNanos
    if (stopping) {
      return
    }

    frameTimeNanos -= activity.getWindowManager().getDefaultDisplay().getAppVsyncOffsetNanos()

    // Don't render unless a new frame is due. This prevents microstutter when streaming
    // at a frame rate that doesn't match the display (such as 60 FPS on 120 Hz).
    val actualFrameTimeDeltaNs = frameTimeNanos - lastRenderedFrameTimeNanos
    val expectedFrameTimeDeltaNs =
      (800000000 / refreshRate).toLong() // within 80% of the next frame
    if (actualFrameTimeDeltaNs >= expectedFrameTimeDeltaNs) {
      // Render up to one frame when in frame pacing mode.
      //
      // NB: Since the queue limit is 2, we won't starve the decoder of output buffers
      // by holding onto them for too long. This also ensures we will have that 1 extra
      // frame of buffer to smooth over network/rendering jitter.
      val nextOutputBuffer = outputBufferQueue.poll()
      if (nextOutputBuffer != null) {
        try {
          videoDecoder!!.releaseOutputBuffer(nextOutputBuffer, frameTimeNanos)

          lastRenderedFrameTimeNanos = frameTimeNanos
          activeWindowVideoStats.totalFramesRendered++
        } catch (ignored: IllegalStateException) {
          try {
            // Try to avoid leaking the output buffer by releasing it without rendering
            videoDecoder!!.releaseOutputBuffer(nextOutputBuffer, false)
          } catch (e: IllegalStateException) {
            // This will leak nextOutputBuffer, but there's really nothing else we can do
            e.printStackTrace()
            handleDecoderException(e)
          }
        }
      }
    }

    // Attempt codec recovery even if we have nothing to render right now. Recovery can still
    // be required even if the codec died before giving any output.
    doCodecRecoveryIfRequired(CR_FLAG_CHOREOGRAPHER)

    // Request another callback for next frame
    Choreographer.getInstance().postFrameCallback(this)
  }

  private fun startChoreographerThread() {
    if (prefs.getFramePacing() != FramePacing.BALANCED) {
      // Not using Choreographer in this pacing mode
      return
    }

    // We use a separate thread to avoid any main thread delays from delaying rendering
    choreographerHandlerThread = HandlerThread(
      "Video - Choreographer",
      Process.THREAD_PRIORITY_DEFAULT + Process.THREAD_PRIORITY_MORE_FAVORABLE
    )
    choreographerHandlerThread!!.start()

    // Start the frame callbacks
    choreographerHandler = Handler(choreographerHandlerThread!!.getLooper())
    choreographerHandler!!.post(object : Runnable {
      override fun run() {
        Choreographer.getInstance().postFrameCallback(this@MediaCodecDecoderRenderer)
      }
    })
  }

  private fun startRendererThread() {
    rendererThread = object : Thread() {
      override fun run() {
        val info = MediaCodec.BufferInfo()
        while (!stopping) {
          try {
            // Try to output a frame
            var outIndex = videoDecoder!!.dequeueOutputBuffer(info, 50000)
            if (outIndex >= 0) {
              var presentationTimeUs = info.presentationTimeUs
              var lastIndex = outIndex

              numFramesOut++

              // Render the latest frame now if frame pacing isn't in balanced mode
              if (prefs.getFramePacing() != FramePacing.BALANCED) {
                // Get the last output buffer in the queue
                while ((videoDecoder!!.dequeueOutputBuffer(info, 0).also { outIndex = it }) >= 0) {
                  videoDecoder!!.releaseOutputBuffer(lastIndex, false)

                  numFramesOut++

                  lastIndex = outIndex
                  presentationTimeUs = info.presentationTimeUs
                }

                if (prefs.getFramePacing() == FramePacing.MAX_SMOOTHNESS ||
                  prefs.getFramePacing() == FramePacing.CAP_FPS
                ) {
                  // In max smoothness or cap FPS mode, we want to never drop frames
                  // Use a PTS that will cause this frame to never be dropped
                  videoDecoder!!.releaseOutputBuffer(lastIndex, 0)
                } else {
                  // Use a PTS that will cause this frame to be dropped if another comes in within
                  // the same V-sync period
                  videoDecoder!!.releaseOutputBuffer(lastIndex, System.nanoTime())
                }

                activeWindowVideoStats.totalFramesRendered++
              } else {
                // For balanced frame pacing case, the Choreographer callback will handle rendering.
                // We just put all frames into the output buffer queue and let it handle things.

                // Discard the oldest buffer if we've exceeded our limit.
                //
                // NB: We have to do this on the producer side because the consumer may not
                // run for a while (if there is a huge mismatch between stream FPS and display
                // refresh rate).

                if (outputBufferQueue.size == OUTPUT_BUFFER_QUEUE_LIMIT) {
                  try {
                    videoDecoder!!.releaseOutputBuffer(outputBufferQueue.take()!!, false)
                  } catch (e: InterruptedException) {
                    // We're shutting down, so we can just drop this buffer on the floor
                    // and it will be reclaimed when the codec is released.
                    return
                  }
                }

                // Add this buffer
                outputBufferQueue.add(lastIndex)
              }

              // Add delta time to the totals (excluding probable outliers)
              val delta = SystemClock.uptimeMillis() - (presentationTimeUs / 1000)
              if (delta >= 0 && delta < 1000) {
                activeWindowVideoStats.decoderTimeMs += delta
                if (!USE_FRAME_RENDER_TIME) {
                  activeWindowVideoStats.totalTimeMs += delta
                }
              }
            } else {
              when (outIndex) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> {}
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                  Timber.i("Output format changed")
                  outputFormat = videoDecoder!!.getOutputFormat()
                  Timber.i("New output format: " + outputFormat)
                }

                else -> {}
              }
            }
          } catch (e: IllegalStateException) {
            handleDecoderException(e)
          } finally {
            doCodecRecoveryIfRequired(CR_FLAG_RENDER_THREAD)
          }
        }
      }
    }
    rendererThread!!.setName("Video - Renderer (MediaCodec)")
    rendererThread!!.setPriority(Thread.NORM_PRIORITY + 2)
    rendererThread!!.start()
  }

  private fun fetchNextInputBuffer(): Boolean {
    val startTime: Long
    val codecRecovered: Boolean

    if (nextInputBuffer != null) {
      // We already have an input buffer
      return true
    }

    startTime = SystemClock.uptimeMillis()

    try {
      // If we don't have an input buffer index yet, fetch one now
      while (nextInputBufferIndex < 0 && !stopping) {
        nextInputBufferIndex = videoDecoder!!.dequeueInputBuffer(10000)
      }

      // Get the backing ByteBuffer for the input buffer index
      if (nextInputBufferIndex >= 0) {
        // Using the new getInputBuffer() API on Lollipop allows
        // the framework to do some performance optimizations for us
        nextInputBuffer = videoDecoder!!.getInputBuffer(nextInputBufferIndex)
        if (nextInputBuffer == null) {
          // According to the Android docs, getInputBuffer() can return null "if the
          // index is not a dequeued input buffer". I don't think this ever should
          // happen but if it does, let's try to get a new input buffer next time.
          nextInputBufferIndex = -1
        }
      }
    } catch (e: IllegalStateException) {
      handleDecoderException(e)
      return false
    } finally {
      codecRecovered = doCodecRecoveryIfRequired(CR_FLAG_INPUT_THREAD)
    }

    // If codec recovery is required, always return false to ensure the caller will request
    // an IDR frame to complete the codec recovery.
    if (codecRecovered) {
      return false
    }

    val deltaMs = (SystemClock.uptimeMillis() - startTime).toInt()

    if (deltaMs >= 20) {
      Timber.w("Dequeue input buffer ran long: " + deltaMs + " ms")
    }

    if (nextInputBuffer == null) {
      // We've been hung for 5 seconds and no other exception was reported,
      // so generate a decoder hung exception
      if (deltaMs >= 5000 && initialException == null) {
        val decoderHungException = DecoderHungException(deltaMs)
        if (!reportedCrash) {
          reportedCrash = true
          crashListener.notifyCrash(decoderHungException)
        }
        throw RendererException(this, decoderHungException)
      }

      return false
    }

    return true
  }

  override fun start() {
    startRendererThread()
    startChoreographerThread()
  }

  // !!! May be called even if setup()/start() fails !!!
  fun prepareForStop() {
    // Let the decoding code know to ignore codec exceptions now
    stopping = true

    // Halt the rendering thread
    if (rendererThread != null) {
      rendererThread!!.interrupt()
    }

    // Stop any active codec recovery operations
    synchronized(codecRecoveryMonitor) {
      codecRecoveryType.set(CR_RECOVERY_TYPE_NONE)
      (codecRecoveryMonitor as Object).notifyAll()
    }

    // Post a quit message to the Choreographer looper (if we have one)
    if (choreographerHandler != null) {
      choreographerHandler!!.post(object : Runnable {
        override fun run() {
          // Don't allow any further messages to be queued
          choreographerHandlerThread!!.quit()

          // Deregister the frame callback (if registered)
          Choreographer.getInstance().removeFrameCallback(this@MediaCodecDecoderRenderer)
        }
      })
    }
  }

  override fun stop() {
    // May be called already, but we'll call it now to be safe
    prepareForStop()

    // Wait for the Choreographer looper to shut down (if we have one)
    if (choreographerHandlerThread != null) {
      try {
        choreographerHandlerThread!!.join()
      } catch (e: InterruptedException) {
        e.printStackTrace()

        // InterruptedException clears the thread's interrupt status. Since we can't
        // handle that here, we will re-interrupt the thread to set the interrupt
        // status back to true.
        Thread.currentThread().interrupt()
      }
    }

    // Wait for the renderer thread to shut down
    try {
      rendererThread!!.join()
    } catch (e: InterruptedException) {
      e.printStackTrace()

      // InterruptedException clears the thread's interrupt status. Since we can't
      // handle that here, we will re-interrupt the thread to set the interrupt
      // status back to true.
      Thread.currentThread().interrupt()
    }
  }

  override fun cleanup() {
    videoDecoder!!.release()
  }

  override fun setHdrMode(enabled: Boolean, hdrMetadata: ByteArray?) {
    // HDR metadata is only supported in Android 7.0 and later, so don't bother
    // restarting the codec on anything earlier than that.
    if (currentHdrMetadata != null && (!enabled || hdrMetadata == null)) {
      currentHdrMetadata = null
    } else if (enabled && hdrMetadata != null && !currentHdrMetadata.contentEquals(hdrMetadata)) {
      currentHdrMetadata = hdrMetadata
    } else {
      // Nothing to do
      return
    }

    // If we reach this point, we need to restart the MediaCodec instance to
    // pick up the HDR metadata change. This will happen on the next input
    // or output buffer.

    // HACK: Reset codec recovery attempt counter, since this is an expected "recovery"
    codecRecoveryAttempts = 0

    // Promote None/Flush to Restart and leave Reset alone
    if (!codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_NONE, CR_RECOVERY_TYPE_RESTART)) {
      codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_FLUSH, CR_RECOVERY_TYPE_RESTART)
    }
  }

  private fun queueNextInputBuffer(timestampUs: Long, codecFlags: Int): Boolean {
    val codecRecovered: Boolean

    try {
      videoDecoder!!.queueInputBuffer(
        nextInputBufferIndex,
        0, nextInputBuffer!!.position(),
        timestampUs, codecFlags
      )

      // We need a new buffer now
      nextInputBufferIndex = -1
      nextInputBuffer = null
    } catch (e: IllegalStateException) {
      if (handleDecoderException(e)) {
        // We encountered a transient error. In this case, just hold onto the buffer
        // (to avoid leaking it), clear it, and keep it for the next frame. We'll return
        // false to trigger an IDR frame to recover.
        nextInputBuffer!!.clear()
      } else {
        // We encountered a non-transient error. In this case, we will simply leak the
        // buffer because we cannot be sure we will ever succeed in queuing it.
        nextInputBufferIndex = -1
        nextInputBuffer = null
      }
      return false
    } finally {
      codecRecovered = doCodecRecoveryIfRequired(CR_FLAG_INPUT_THREAD)
    }

    // If codec recovery is required, always return false to ensure the caller will request
    // an IDR frame to complete the codec recovery.
    if (codecRecovered) {
      return false
    }

    // Fetch a new input buffer now while we have some time between frames
    // to have it ready immediately when the next frame arrives.
    //
    // We must propagate the return value here in order to properly handle
    // codec recovery happening in fetchNextInputBuffer(). If we don't, we'll
    // never get an IDR frame to complete the recovery process.
    return fetchNextInputBuffer()
  }

  private fun doProfileSpecificSpsPatching(sps: SeqParameterSet) {
    // Some devices benefit from setting constraint flags 4 & 5 to make this Constrained
    // High Profile which allows the decoder to assume there will be no B-frames and
    // reduce delay and buffering accordingly. Some devices (Marvell, Exynos 4) don't
    // like it so we only set them on devices that are confirmed to benefit from it.
    if (sps.profileIdc == 100 && constrainedHighProfile) {
      Timber.i("Setting constraint set flags for constrained high profile")
      sps.constraintSet4Flag = true
      sps.constraintSet5Flag = true
    } else {
      // Force the constraints unset otherwise (some may be set by default)
      sps.constraintSet4Flag = false
      sps.constraintSet5Flag = false
    }
  }

  @Suppress("deprecation")
  override fun submitDecodeUnit(
    decodeUnitData: ByteArray, decodeUnitLength: Int, decodeUnitType: Int,
    frameNumber: Int, frameType: Int, frameHostProcessingLatency: Char,
    receiveTimeMs: Long, enqueueTimeMs: Long
  ): Int {
    if (stopping) {
      // Don't bother if we're stopping
      return MoonBridge.DR_OK
    }

    if (lastFrameNumber == 0) {
      activeWindowVideoStats.measurementStartTimestamp = SystemClock.uptimeMillis()
    } else if (frameNumber != lastFrameNumber && frameNumber != lastFrameNumber + 1) {
      // We can receive the same "frame" multiple times if it's an IDR frame.
      // In that case, each frame start NALU is submitted independently.
      activeWindowVideoStats.framesLost += frameNumber - lastFrameNumber - 1
      activeWindowVideoStats.totalFrames += frameNumber - lastFrameNumber - 1
      activeWindowVideoStats.frameLossEvents++
    }

    // Reset CSD data for each IDR frame
    if (lastFrameNumber != frameNumber && frameType == MoonBridge.FRAME_TYPE_IDR) {
      vpsBuffers.clear()
      spsBuffers.clear()
      ppsBuffers.clear()
    }

    lastFrameNumber = frameNumber

    // Flip stats windows roughly every second
    if (SystemClock.uptimeMillis() >= activeWindowVideoStats.measurementStartTimestamp + 1000) {
      if (prefs.getEnablePerfOverlay()) {
        val lastTwo = VideoStats()
        lastTwo.add(lastWindowVideoStats)
        lastTwo.add(activeWindowVideoStats)
        val fps = lastTwo.getFps()
        val decoder: String?

        if ((this.activeVideoFormat and MoonBridge.VIDEO_FORMAT_MASK_H264) != 0) {
          decoder = avcDecoder!!.getName()
        } else if ((this.activeVideoFormat and MoonBridge.VIDEO_FORMAT_MASK_H265) != 0) {
          decoder = hevcDecoder!!.getName()
        } else if ((this.activeVideoFormat and MoonBridge.VIDEO_FORMAT_MASK_AV1) != 0) {
          decoder = av1Decoder!!.getName()
        } else {
          decoder = "(unknown)"
        }

        val decodeTimeMs = lastTwo.decoderTimeMs.toFloat() / lastTwo.totalFramesReceived
        val rttInfo = MoonBridge.getEstimatedRttInfo()
        val performanceInfo = PerformanceInfo(
          initialWidth,
          initialHeight,
          fps.totalFps,
          decoder,
          fps.receivedFps,
          fps.renderedFps,
          lastTwo.framesLost.toFloat() / lastTwo.totalFrames * 100,
          (rttInfo shr 32).toInt(),
          rttInfo.toInt(),
          if (lastTwo.framesWithHostProcessingLatency > 0) {
            PerformanceInfo.HostProcessingLatencies(
              lastTwo.minHostProcessingLatency.code.toFloat() / 10,
              lastTwo.maxHostProcessingLatency.code.toFloat() / 10,
              lastTwo.totalHostProcessingLatency.toFloat() / 10 / lastTwo.framesWithHostProcessingLatency
            )
          } else null,
          decodeTimeMs
        )
        perfListener.onPerfUpdate(performanceInfo)
//        val sb = StringBuilder()
//        sb.append(
//          activity.getString(
//            R.string.f_perf_overlay_streamdetails,
//            initialWidth.toString() + "x" + initialHeight,
//            fps.totalFps
//          )
//        ).append('\n')
//        sb.append(activity.getString(R.string.f_perf_overlay_decoder, decoder)).append('\n')
//        sb.append(activity.getString(R.string.f_perf_overlay_incomingfps, fps.receivedFps))
//          .append('\n')
//        sb.append(activity.getString(R.string.f_perf_overlay_renderingfps, fps.renderedFps))
//          .append('\n')
//        sb.append(
//          activity.getString(
//            R.string.f_perf_overlay_netdrops,
//            lastTwo.framesLost.toFloat() / lastTwo.totalFrames * 100
//          )
//        ).append('\n')
//        sb.append(
//          activity.getString(
//            R.string.f_perf_overlay_netlatency,
//            (rttInfo shr 32).toInt(), rttInfo.toInt()
//          )
//        ).append('\n')
//        if (lastTwo.framesWithHostProcessingLatency > 0) {
//          sb.append(
//            activity.getString(
//              R.string.f_perf_overlay_hostprocessinglatency,
//              lastTwo.minHostProcessingLatency.code.toFloat() / 10,
//              lastTwo.maxHostProcessingLatency.code.toFloat() / 10,
//              lastTwo.totalHostProcessingLatency.toFloat() / 10 / lastTwo.framesWithHostProcessingLatency
//            )
//          ).append('\n')
//        }
//        sb.append(activity.getString(R.string.f_perf_overlay_dectime, decodeTimeMs))

      }

      globalVideoStats.add(activeWindowVideoStats)
      lastWindowVideoStats.copy(activeWindowVideoStats)
      activeWindowVideoStats.clear()
      activeWindowVideoStats.measurementStartTimestamp = SystemClock.uptimeMillis()
    }

    var csdSubmittedForThisFrame = false

    // IDR frames require special handling for CSD buffer submission
    if (frameType == MoonBridge.FRAME_TYPE_IDR) {
      // H264 SPS
      if (decodeUnitType == MoonBridge.BUFFER_TYPE_SPS && (this.activeVideoFormat and MoonBridge.VIDEO_FORMAT_MASK_H264) != 0) {
        numSpsIn++

        val spsBuf = ByteBuffer.wrap(decodeUnitData)
        val startSeqLen = if (decodeUnitData[2].toInt() == 0x01) 3 else 4

        // Skip to the start of the NALU data
        spsBuf.position(startSeqLen + 1)

        // The H264Utils.readSPS function safely handles
        // Annex B NALUs (including NALUs with escape sequences)
        val sps = H264Utils.readSPS(spsBuf)

        // Some decoders rely on H264 level to decide how many buffers are needed
        // Since we only need one frame buffered, we'll set the level as low as we can
        // for known resolution combinations. Reference frame invalidation may need
        // these, so leave them be for those decoders.
        if (!refFrameInvalidationActive) {
          if (initialWidth <= 720 && initialHeight <= 480 && refreshRate <= 60) {
            // Max 5 buffered frames at 720x480x60
            Timber.i("Patching level_idc to 31")
            sps.levelIdc = 31
          } else if (initialWidth <= 1280 && initialHeight <= 720 && refreshRate <= 60) {
            // Max 5 buffered frames at 1280x720x60
            Timber.i("Patching level_idc to 32")
            sps.levelIdc = 32
          } else if (initialWidth <= 1920 && initialHeight <= 1080 && refreshRate <= 60) {
            // Max 4 buffered frames at 1920x1080x64
            Timber.i("Patching level_idc to 42")
            sps.levelIdc = 42
          } else {
            // Leave the profile alone (currently 5.0)
          }
        }

        // TI OMAP4 requires a reference frame count of 1 to decode successfully. Exynos 4
        // also requires this fixup.
        //
        // I'm doing this fixup for all devices because I haven't seen any devices that
        // this causes issues for. At worst, it seems to do nothing and at best it fixes
        // issues with video lag, hangs, and crashes.
        //
        // It does break reference frame invalidation, so we will not do that for decoders
        // where we've enabled reference frame invalidation.
        if (!refFrameInvalidationActive) {
          Timber.i("Patching num_ref_frames in SPS")
          sps.numRefFrames = 1
        }


        // Some older devices used to choke on a bitstream restrictions, so we won't provide them
        // unless explicitly whitelisted. For newer devices, leave the bitstream restrictions present.
        // The SPS that comes in the current H264 bytestream doesn't set bitstream_restriction_flag
        // or max_dec_frame_buffering which increases decoding latency on Tegra.

        // If the encoder didn't include VUI parameters in the SPS, add them now

        if (sps.vuiParams == null) {
          Timber.i("Adding VUI parameters")
          sps.vuiParams = VUIParameters()
        }

        // GFE 2.5.11 started sending bitstream restrictions
        if (sps.vuiParams.bitstreamRestriction == null) {
          Timber.i("Adding bitstream restrictions")
          sps.vuiParams.bitstreamRestriction = BitstreamRestriction()
          sps.vuiParams.bitstreamRestriction.motionVectorsOverPicBoundariesFlag = true
          sps.vuiParams.bitstreamRestriction.maxBytesPerPicDenom = 2
          sps.vuiParams.bitstreamRestriction.maxBitsPerMbDenom = 1
          sps.vuiParams.bitstreamRestriction.log2MaxMvLengthHorizontal = 16
          sps.vuiParams.bitstreamRestriction.log2MaxMvLengthVertical = 16
          sps.vuiParams.bitstreamRestriction.numReorderFrames = 0
        } else {
          Timber.i("Patching bitstream restrictions")
        }

        // Some devices throw errors if maxDecFrameBuffering < numRefFrames
        sps.vuiParams.bitstreamRestriction.maxDecFrameBuffering = sps.numRefFrames

        // These values are the defaults for the fields, but they are more aggressive
        // than what GFE sends in 2.5.11, but it doesn't seem to cause picture problems.
        // We'll leave these alone for "modern" devices just in case they care.

        // log2_max_mv_length_horizontal and log2_max_mv_length_vertical are set to more
        // conservative values by GFE 2.5.11. We'll let those values stand.

        // If we need to hack this SPS to say we're baseline, do so now
        if (needsBaselineSpsHack) {
          Timber.i("Hacking SPS to baseline")
          sps.profileIdc = 66
          savedSps = sps
        }

        // Patch the SPS constraint flags
        doProfileSpecificSpsPatching(sps)

        // The H264Utils.writeSPS function safely handles
        // Annex B NALUs (including NALUs with escape sequences)
        val escapedNalu = H264Utils.writeSPS(sps, decodeUnitLength)

        // Construct the patched SPS
        val naluBuffer = ByteArray(startSeqLen + 1 + escapedNalu.limit())
        System.arraycopy(decodeUnitData, 0, naluBuffer, 0, startSeqLen + 1)
        escapedNalu.get(naluBuffer, startSeqLen + 1, escapedNalu.limit())

        // Batch this to submit together with other CSD per AOSP docs
        spsBuffers.add(naluBuffer)
        return MoonBridge.DR_OK
      } else if (decodeUnitType == MoonBridge.BUFFER_TYPE_VPS) {
        numVpsIn++

        // Batch this to submit together with other CSD per AOSP docs
        val naluBuffer = ByteArray(decodeUnitLength)
        System.arraycopy(decodeUnitData, 0, naluBuffer, 0, decodeUnitLength)
        vpsBuffers.add(naluBuffer)
        return MoonBridge.DR_OK
      } else if (decodeUnitType == MoonBridge.BUFFER_TYPE_SPS) {
        numSpsIn++

        // Batch this to submit together with other CSD per AOSP docs
        val naluBuffer = ByteArray(decodeUnitLength)
        System.arraycopy(decodeUnitData, 0, naluBuffer, 0, decodeUnitLength)
        spsBuffers.add(naluBuffer)
        return MoonBridge.DR_OK
      } else if (decodeUnitType == MoonBridge.BUFFER_TYPE_PPS) {
        numPpsIn++

        // Batch this to submit together with other CSD per AOSP docs
        val naluBuffer = ByteArray(decodeUnitLength)
        System.arraycopy(decodeUnitData, 0, naluBuffer, 0, decodeUnitLength)
        ppsBuffers.add(naluBuffer)
        return MoonBridge.DR_OK
      } else if ((this.activeVideoFormat and (MoonBridge.VIDEO_FORMAT_MASK_H264 or MoonBridge.VIDEO_FORMAT_MASK_H265)) != 0) {
        // If this is the first CSD blob or we aren't supporting fused IDR frames, we will
        // submit the CSD blob in a separate input buffer for each IDR frame.
        if (!submittedCsd || !fusedIdrFrame) {
          if (!fetchNextInputBuffer()) {
            return MoonBridge.DR_NEED_IDR
          }

          // Submit all CSD when we receive the first non-CSD blob in an IDR frame
          for (vpsBuffer in vpsBuffers) {
            nextInputBuffer!!.put(vpsBuffer)
          }
          for (spsBuffer in spsBuffers) {
            nextInputBuffer!!.put(spsBuffer)
          }
          for (ppsBuffer in ppsBuffers) {
            nextInputBuffer!!.put(ppsBuffer)
          }

          if (!queueNextInputBuffer(0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)) {
            return MoonBridge.DR_NEED_IDR
          }

          // Remember that we already submitted CSD for this frame, so we don't do it
          // again in the fused IDR case below.
          csdSubmittedForThisFrame = true

          // Remember that we submitted CSD globally for this MediaCodec instance
          submittedCsd = true

          if (needsBaselineSpsHack) {
            needsBaselineSpsHack = false

            if (!replaySps()) {
              return MoonBridge.DR_NEED_IDR
            }

            Timber.i("SPS replay complete")
          }
        }
      }
    }

    if (frameHostProcessingLatency.code != 0) {
      if (activeWindowVideoStats.minHostProcessingLatency.code != 0) {
        activeWindowVideoStats.minHostProcessingLatency = min(
          activeWindowVideoStats.minHostProcessingLatency.code,
          frameHostProcessingLatency.code
        ).toChar()
      } else {
        activeWindowVideoStats.minHostProcessingLatency = frameHostProcessingLatency
      }
      activeWindowVideoStats.framesWithHostProcessingLatency += 1
    }
    activeWindowVideoStats.maxHostProcessingLatency = max(
      activeWindowVideoStats.maxHostProcessingLatency.code,
      frameHostProcessingLatency.code
    ).toChar()
    activeWindowVideoStats.totalHostProcessingLatency += frameHostProcessingLatency.code

    activeWindowVideoStats.totalFramesReceived++
    activeWindowVideoStats.totalFrames++

    if (!FRAME_RENDER_TIME_ONLY) {
      // Count time from first packet received to enqueue time as receive time
      // We will count DU queue time as part of decoding, because it is directly
      // caused by a slow decoder.
      activeWindowVideoStats.totalTimeMs += enqueueTimeMs - receiveTimeMs
    }

    if (!fetchNextInputBuffer()) {
      return MoonBridge.DR_NEED_IDR
    }

    var codecFlags = 0

    if (frameType == MoonBridge.FRAME_TYPE_IDR) {
      codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_SYNC_FRAME

      // If we are using fused IDR frames, submit the CSD with each IDR frame
      if (fusedIdrFrame && !csdSubmittedForThisFrame) {
        for (vpsBuffer in vpsBuffers) {
          nextInputBuffer!!.put(vpsBuffer)
        }
        for (spsBuffer in spsBuffers) {
          nextInputBuffer!!.put(spsBuffer)
        }
        for (ppsBuffer in ppsBuffers) {
          nextInputBuffer!!.put(ppsBuffer)
        }
      }
    }

    var timestampUs = enqueueTimeMs * 1000
    if (timestampUs <= lastTimestampUs) {
      // We can't submit multiple buffers with the same timestamp
      // so bump it up by one before queuing
      timestampUs = lastTimestampUs + 1
    }
    lastTimestampUs = timestampUs

    numFramesIn++

    if (decodeUnitLength > nextInputBuffer!!.limit() - nextInputBuffer!!.position()) {
      val exception = IllegalArgumentException(
        "Decode unit length " + decodeUnitLength + " too large for input buffer " + nextInputBuffer!!.limit()
      )
      if (!reportedCrash) {
        reportedCrash = true
        crashListener.notifyCrash(exception)
      }
      throw RendererException(this, exception)
    }

    // Copy data from our buffer list into the input buffer
    nextInputBuffer!!.put(decodeUnitData, 0, decodeUnitLength)

    if (!queueNextInputBuffer(timestampUs, codecFlags)) {
      return MoonBridge.DR_NEED_IDR
    }

    return MoonBridge.DR_OK
  }

  private fun replaySps(): Boolean {
    if (!fetchNextInputBuffer()) {
      return false
    }

    // Write the Annex B header
    nextInputBuffer!!.put(byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x67))

    // Switch the H264 profile back to high
    savedSps!!.profileIdc = 100

    // Patch the SPS constraint flags
    doProfileSpecificSpsPatching(savedSps!!)

    // The H264Utils.writeSPS function safely handles
    // Annex B NALUs (including NALUs with escape sequences)
    val escapedNalu = H264Utils.writeSPS(savedSps, 128)
    nextInputBuffer!!.put(escapedNalu)

    // No need for the SPS anymore
    savedSps = null

    // Queue the new SPS
    return queueNextInputBuffer(0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
  }

  override fun getCapabilities(): Int {
    var capabilities = 0

    // Request the optimal number of slices per frame for this decoder
    capabilities = capabilities or MoonBridge.CAPABILITY_SLICES_PER_FRAME(optimalSlicesPerFrame)

    // Enable reference frame invalidation on supported hardware
    if (refFrameInvalidationAvc) {
      capabilities = capabilities or MoonBridge.CAPABILITY_REFERENCE_FRAME_INVALIDATION_AVC
    }
    if (refFrameInvalidationHevc) {
      capabilities = capabilities or MoonBridge.CAPABILITY_REFERENCE_FRAME_INVALIDATION_HEVC
    }
    if (refFrameInvalidationAv1) {
      capabilities = capabilities or MoonBridge.CAPABILITY_REFERENCE_FRAME_INVALIDATION_AV1
    }

    // Enable direct submit on supported hardware
    if (directSubmit) {
      capabilities = capabilities or MoonBridge.CAPABILITY_DIRECT_SUBMIT
    }

    return capabilities
  }

  val averageEndToEndLatency: Int
    get() {
      if (globalVideoStats.totalFramesReceived == 0) {
        return 0
      }
      return (globalVideoStats.totalTimeMs / globalVideoStats.totalFramesReceived).toInt()
    }

  val averageDecoderLatency: Int
    get() {
      if (globalVideoStats.totalFramesReceived == 0) {
        return 0
      }
      return (globalVideoStats.decoderTimeMs / globalVideoStats.totalFramesReceived).toInt()
    }

  internal class DecoderHungException(private val hangTimeMs: Int) : RuntimeException() {
    override fun toString(): String {
      var str = ""

      str += "Hang time: " + hangTimeMs + " ms" + RendererException.Companion.DELIMITER
      str += super.toString()

      return str
    }
  }

  internal class RendererException(renderer: MediaCodecDecoderRenderer, e: Exception) :
    RuntimeException() {
    private val text: String

    init {
      this.text = generateText(renderer, e)
    }

    override fun toString(): String {
      return text
    }

    private fun generateText(
      renderer: MediaCodecDecoderRenderer,
      originalException: Exception
    ): String {
      var str: String

      if (renderer.numVpsIn == 0 && renderer.numSpsIn == 0 && renderer.numPpsIn == 0) {
        str = "PreSPSError"
      } else if (renderer.numSpsIn > 0 && renderer.numPpsIn == 0) {
        str = "PrePPSError"
      } else if (renderer.numPpsIn > 0 && renderer.numFramesIn == 0) {
        str = "PreIFrameError"
      } else if (renderer.numFramesIn > 0 && renderer.outputFormat == null) {
        str = "PreOutputConfigError"
      } else if (renderer.outputFormat != null && renderer.numFramesOut == 0) {
        str = "PreOutputError"
      } else if (renderer.numFramesOut <= renderer.refreshRate * 30) {
        str = "EarlyOutputError"
      } else {
        str = "ErrorWhileStreaming"
      }

      str += "Format: " + String.format("%x", renderer.activeVideoFormat) + DELIMITER
      str += "AVC Decoder: " + (if (renderer.avcDecoder != null) renderer.avcDecoder!!.getName() else "(none)") + DELIMITER
      str += "HEVC Decoder: " + (if (renderer.hevcDecoder != null) renderer.hevcDecoder!!.getName() else "(none)") + DELIMITER
      str += "AV1 Decoder: " + (if (renderer.av1Decoder != null) renderer.av1Decoder!!.getName() else "(none)") + DELIMITER
      if (true && renderer.avcDecoder != null) {
        val avcWidthRange =
          renderer.avcDecoder!!.getCapabilitiesForType("video/avc").getVideoCapabilities()!!
            .getSupportedWidths()
        str += "AVC supported width range: " + avcWidthRange + DELIMITER
        try {
          val avcFpsRange =
            renderer.avcDecoder!!.getCapabilitiesForType("video/avc").getVideoCapabilities()!!
              .getAchievableFrameRatesFor(renderer.initialWidth, renderer.initialHeight)
          str += "AVC achievable FPS range: " + avcFpsRange + DELIMITER
        } catch (e: IllegalArgumentException) {
          str += "AVC achievable FPS range: UNSUPPORTED!" + DELIMITER
        }
      }
      if (renderer.hevcDecoder != null) {
        val hevcWidthRange =
          renderer.hevcDecoder!!.getCapabilitiesForType("video/hevc").getVideoCapabilities()!!
            .getSupportedWidths()
        str += "HEVC supported width range: " + hevcWidthRange + DELIMITER
        try {
          val hevcFpsRange =
            renderer.hevcDecoder!!.getCapabilitiesForType("video/hevc").getVideoCapabilities()!!
              .getAchievableFrameRatesFor(renderer.initialWidth, renderer.initialHeight)
          str += "HEVC achievable FPS range: " + hevcFpsRange + DELIMITER
        } catch (e: IllegalArgumentException) {
          str += "HEVC achievable FPS range: UNSUPPORTED!" + DELIMITER
        }
      }
      if (renderer.av1Decoder != null) {
        val av1WidthRange =
          renderer.av1Decoder!!.getCapabilitiesForType("video/av01").getVideoCapabilities()!!
            .getSupportedWidths()
        str += "AV1 supported width range: " + av1WidthRange + DELIMITER
        try {
          val av1FpsRange =
            renderer.av1Decoder!!.getCapabilitiesForType("video/av01").getVideoCapabilities()!!
              .getAchievableFrameRatesFor(renderer.initialWidth, renderer.initialHeight)
          str += "AV1 achievable FPS range: " + av1FpsRange + DELIMITER
        } catch (e: IllegalArgumentException) {
          str += "AV1 achievable FPS range: UNSUPPORTED!" + DELIMITER
        }
      }
      str += "Configured format: " + renderer.configuredFormat + DELIMITER
      str += "Input format: " + renderer.inputFormat + DELIMITER
      str += "Output format: " + renderer.outputFormat + DELIMITER
      str += "Adaptive playback: " + renderer.adaptivePlayback + DELIMITER
      str += "GL Renderer: " + renderer.glRenderer + DELIMITER
      //str += "Build fingerprint: "+Build.FINGERPRINT+DELIMITER;
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        str += "SOC: " + Build.SOC_MANUFACTURER + " - " + Build.SOC_MODEL + DELIMITER
        str += "Performance class: " + Build.VERSION.MEDIA_PERFORMANCE_CLASS + DELIMITER
        /*str += "Vendor params: ";
                List<String> params = renderer.videoDecoder.getSupportedVendorParameters();
                if (params.isEmpty()) {
                    str += "NONE";
                }
                else {
                    for (String param : params) {
                        str += param + " ";
                    }
                }
                str += DELIMITER;*/
      }
      str += "Consecutive crashes: " + renderer.consecutiveCrashCount + DELIMITER
      str += "RFI active: " + renderer.refFrameInvalidationActive + DELIMITER
      str += "Using modern SPS patching: true$DELIMITER"
      str += "Fused IDR frames: " + renderer.fusedIdrFrame + DELIMITER
      str += "Video dimensions: " + renderer.initialWidth + "x" + renderer.initialHeight + DELIMITER
      str += "FPS target: " + renderer.refreshRate + DELIMITER
      str += "Bitrate: " + renderer.prefs.bitrate * 1000 + " Kbps" + DELIMITER
      str += "CSD stats: " + renderer.numVpsIn + ", " + renderer.numSpsIn + ", " + renderer.numPpsIn + DELIMITER
      str += "Frames in-out: " + renderer.numFramesIn + ", " + renderer.numFramesOut + DELIMITER
      str += "Total frames received: " + renderer.globalVideoStats.totalFramesReceived + DELIMITER
      str += "Total frames rendered: " + renderer.globalVideoStats.totalFramesRendered + DELIMITER
      str += "Frame losses: " + renderer.globalVideoStats.framesLost + " in " + renderer.globalVideoStats.frameLossEvents + " loss events" + DELIMITER
      str += "Average end-to-end client latency: " + renderer.averageEndToEndLatency + "ms" + DELIMITER
      str += "Average hardware decoder latency: " + renderer.averageDecoderLatency + "ms" + DELIMITER
      str += "Frame pacing mode: " + renderer.prefs.getFramePacing() + DELIMITER

      if (originalException is CodecException) {
        val ce = originalException

        str += "Diagnostic Info: " + ce.getDiagnosticInfo() + DELIMITER
        str += "Recoverable: " + ce.isRecoverable() + DELIMITER
        str += "Transient: " + ce.isTransient() + DELIMITER

        str += "Codec Error Code: " + ce.getErrorCode() + DELIMITER
      }

      str += originalException.toString()

      return str
    }

    companion object {
      private const val serialVersionUID = 8985937536997012406L
      val DELIMITER: String = if (BuildConfig.DEBUG) "\n" else " | "
    }
  }

  companion object {
    private const val USE_FRAME_RENDER_TIME = false
    private val FRAME_RENDER_TIME_ONLY = USE_FRAME_RENDER_TIME && false

    private const val CR_MAX_TRIES = 10
    private const val CR_RECOVERY_TYPE_NONE = 0
    private const val CR_RECOVERY_TYPE_FLUSH = 1
    private const val CR_RECOVERY_TYPE_RESTART = 2
    private const val CR_RECOVERY_TYPE_RESET = 3

    // Each thread that touches the MediaCodec object or any associated buffers must have a flag
    // here and must call doCodecRecoveryIfRequired() on a regular basis.
    private const val CR_FLAG_INPUT_THREAD = 0x1
    private const val CR_FLAG_RENDER_THREAD = 0x2
    private const val CR_FLAG_CHOREOGRAPHER = 0x4
    private val CR_FLAG_ALL: Int =
      CR_FLAG_INPUT_THREAD or CR_FLAG_RENDER_THREAD or CR_FLAG_CHOREOGRAPHER
    private const val EXCEPTION_REPORT_DELAY_MS = 3000

    private const val OUTPUT_BUFFER_QUEUE_LIMIT = 2
  }

  data class PerformanceInfo(
    val width: Int,
    val height: Int,
    val totalFps: Float,
    val decoder: String,
    val receivedFps: Float,
    val renderedFps: Float,
    val networkFrameDropPercentage: Float,
    val networkLatencyMs: Int,
    val networkLatencyVarianceMs: Int,
    val hostProcessingLatencies: HostProcessingLatencies?,
    val averageDecodingTimeMs: Float,
  ) {
    class HostProcessingLatencies(
      val min: Float,
      val max: Float,
      val average: Float
    )
  }
}
