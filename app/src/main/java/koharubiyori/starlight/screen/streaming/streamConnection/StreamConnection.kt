package koharubiyori.starlight.screen.streaming.streamConnection

import android.app.Activity
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.hardware.input.InputManager
import android.net.ConnectivityManager
import android.os.Build
import android.util.Rational
import android.view.Display
import androidx.activity.ComponentActivity
import androidx.core.app.PictureInPictureParamsCompat
import androidx.core.content.ContextCompat
import androidx.core.pip.BasicPictureInPicture
import androidx.core.pip.PictureInPictureDelegate
import koharubiyori.starlight.Globals
import koharubiyori.starlight.R
import koharubiyori.starlight.nvstream.AndroidCryptoProvider
import koharubiyori.starlight.nvstream.NvConnection
import koharubiyori.starlight.nvstream.StreamConfiguration
import koharubiyori.starlight.nvstream.http.NvApp
import koharubiyori.starlight.nvstream.jni.MoonBridge
import koharubiyori.starlight.screen.home.util.ComputerDetailsEx
import koharubiyori.starlight.screen.settings.SettingsScreenUtils
import koharubiyori.starlight.screen.streaming.util.StreamView
import koharubiyori.starlight.screen.streaming.util.UsbDriverServiceConnection
import koharubiyori.starlight.screen.streaming.util.input.ControllerHandler
import koharubiyori.starlight.screen.streaming.util.input.KeyboardTranslator
import koharubiyori.starlight.screen.streaming.util.input.capture.InputCaptureManager
import koharubiyori.starlight.screen.streaming.util.input.capture.InputCaptureProvider
import koharubiyori.starlight.screen.streaming.util.input.driver.UsbDriverService
import koharubiyori.starlight.screen.streaming.util.input.touch.AbsoluteTouchContext
import koharubiyori.starlight.screen.streaming.util.input.touch.RelativeTouchContext
import koharubiyori.starlight.screen.streaming.util.input.touch.TouchContext
import koharubiyori.starlight.screen.streaming.util.input.virtual_controller.VirtualController
import koharubiyori.starlight.screen.streaming.util.video.MediaCodecDecoderRenderer
import koharubiyori.starlight.screen.streaming.util.video.MediaCodecHelper
import koharubiyori.starlight.store.ReminderStore
import koharubiyori.starlight.store.proto.AudioConfiguration
import koharubiyori.starlight.store.proto.FormatOption
import koharubiyori.starlight.store.proto.FramePacing
import koharubiyori.starlight.store.proto.ReminderStoreData
import koharubiyori.starlight.screen.settings.bitrate
import koharubiyori.starlight.store.proto.SettingsStoreData
import koharubiyori.starlight.store.proto.copy
import koharubiyori.starlight.util.glRenderer
import koharubiyori.starlight.util.toast
import koharubiyori.starlight.util.uniqueId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.Executors
import kotlin.experimental.or
import kotlin.math.min
import kotlin.math.roundToInt

class StreamConnection(
  val computer: ComputerDetailsEx,
  val app: NvApp,
  val streamView: StreamView,
  private var reminderStoreData: ReminderStoreData,
  var settingsStoreData: SettingsStoreData,
  private val reminderStore: ReminderStore,
  val activity: Activity,
  val coroutineScope: CoroutineScope,
) {
  lateinit var decoderRenderer: MediaCodecDecoderRenderer
  lateinit var nvConnection: NvConnection
  lateinit var controllerHandler: ControllerHandler
  lateinit var keyboardTranslator: KeyboardTranslator
  lateinit var inputCaptureListener: InputCaptureListener
  var virtualController: VirtualController? = null
  lateinit var inputCaptureProvider: InputCaptureProvider

  val statusFlow = MutableStateFlow(Status.INITIAL)
  val notificationFlow = MutableSharedFlow<String>()
  val inPipModeFlow = MutableStateFlow(false)
  val performanceInfoFlow = MutableStateFlow<MediaCodecDecoderRenderer.PerformanceInfo?>(null)
  private var pictureInPicture: BasicPictureInPicture? = null
  private var usbDriverServiceConnection: UsbDriverServiceConnection? = null

  private var suppressPipRefCount = 0
  private val touchContextMap = arrayOfNulls<TouchContext>(2)
  private var desiredRefreshRate = 60f
  var reportedCrash = false
    private set
  var errorMessage: ErrorMessage? = null
    private set


  fun getTouchContext(actionIndex: Int): TouchContext? =
    touchContextMap.getOrNull(actionIndex)

  fun initialize() {
    val context = activity.applicationContext

    val connMgr = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    if (connMgr.isActiveNetworkMetered) {
      toast(context, activity.getString(R.string.s_metered_dialog))
    }

    MediaCodecHelper.initialize(context, glRenderer)

    var willStreamHdr = run {
      if (!settingsStoreData.enableHdr) return@run false
      val hdrCaps = activity.display.hdrCapabilities ?: return@run false
      // We must now ensure our display is compatible with HDR10
      for (hdrType in hdrCaps.supportedHdrTypes) {
        if (hdrType == Display.HdrCapabilities.HDR_TYPE_HDR10) return@run true
      }

      false
    }

    if (settingsStoreData.enableHdr && !willStreamHdr) {
      toast(context, activity.getString(R.string.s_hdr_unsupported))
    }

    val crashListener = object : MediaCodecDecoderRenderer.CrashListener {
      override fun notifyCrash(e: Exception) {
        // The MediaCodec instance is going down due to a crash
        // let's tell the user something when they open the app again

        // We must use commit because the app will crash when we return from this function

        coroutineScope.launch {
          reminderStore.updateReminder { mediaCodecDecoderRendererCrashCount++ }
        }
        reportedCrash = true
      }
    }

    val perfOverlayListener = object : MediaCodecDecoderRenderer.PerfOverlayListener {
      override fun onPerfUpdate(performanceInfo: MediaCodecDecoderRenderer.PerformanceInfo) {
        performanceInfoFlow.value = performanceInfo
      }
    }

    decoderRenderer = MediaCodecDecoderRenderer(
      activity,
      settingsStoreData,
      crashListener,
      reminderStoreData.mediaCodecDecoderRendererCrashCount,
      connMgr.isActiveNetworkMetered,
      willStreamHdr,
      glRenderer,
      perfOverlayListener
    )

    // Don't stream HDR if the decoder can't support it
    if (willStreamHdr && !decoderRenderer.isHevcMain10Hdr10Supported && !decoderRenderer.isAv1Main10Supported) {
      willStreamHdr = false
      toast(context, activity.getString(R.string.s_hdr_unsupported))
    }

    // Display a message to the user if HEVC was forced on but we still didn't find a decoder
    if (settingsStoreData.videoFormat === FormatOption.HEVC && !decoderRenderer.isHevcSupported) {
      toast(context, activity.getString(R.string.s_decoder_hevc_unsupported))
    }

    // Display a message to the user if AV1 was forced on but we still didn't find a decoder
    if (settingsStoreData.videoFormat === FormatOption.AV1 && !decoderRenderer.isAv1Supported) {
      toast(context, activity.getString(R.string.s_decoder_av1_unsupported))
    }

    // H.264 is always supported
    val supportedVideoFormats = run {
      var formats = MoonBridge.VIDEO_FORMAT_H264
      if (decoderRenderer.isHevcSupported) {
        formats = formats or MoonBridge.VIDEO_FORMAT_H265
        if (willStreamHdr && decoderRenderer.isHevcMain10Hdr10Supported) {
          formats = formats or MoonBridge.VIDEO_FORMAT_H265_MAIN10
        }
      }
      if (decoderRenderer.isAv1Supported) {
        formats = formats or MoonBridge.VIDEO_FORMAT_AV1_MAIN8
        if (willStreamHdr && decoderRenderer.isAv1Main10Supported) {
          formats = formats or MoonBridge.VIDEO_FORMAT_AV1_MAIN10
        }
      }
      formats
    }

    val gamepadMask = run {
      var gamepadMask = ControllerHandler.getAttachedControllerMask(context)
      if (!settingsStoreData.multiController) {
        // Always set gamepad 1 present for when multi-controller is
        // disabled for games that don't properly support detection
        // of gamepads removed and replugged at runtime.
        gamepadMask = 1
      }
      if (settingsStoreData.onscreenController) {
        // If we're using OSC, always set at least gamepad 1.
        gamepadMask = gamepadMask or 1
      }
      gamepadMask
    }


    // Set to the optimal mode for streaming
    val displayRefreshRate = prepareDisplayForRendering(activity, streamView)
    Timber.i("Display refresh rate: %s", displayRefreshRate)

    // If the user requested frame pacing using a capped FPS, we will need to change our
    // desired FPS setting here in accordance with the active display refresh rate.
    val roundedRefreshRate = displayRefreshRate.roundToInt()
    var chosenFrameRate = settingsStoreData.fps
    if (settingsStoreData.framePacing === FramePacing.CAP_FPS) {
      if (settingsStoreData.fps >= roundedRefreshRate) {
        if (settingsStoreData.fps > roundedRefreshRate + 3) {
          // Use frame drops when rendering above the screen frame rate
          settingsStoreData = settingsStoreData.copy { framePacing = FramePacing.BALANCED }
          Timber.i("Using drop mode for FPS > Hz")
        } else if (roundedRefreshRate <= 49) {
          // Let's avoid clearly bogus refresh rates and fall back to legacy rendering
          settingsStoreData = settingsStoreData.copy { framePacing = FramePacing.BALANCED }
          Timber.i("Bogus refresh rate: %s", roundedRefreshRate)
        } else {
          chosenFrameRate = roundedRefreshRate - 1
          Timber.i("Adjusting FPS target for screen to %s", chosenFrameRate)
        }
      }
    }

    val config = StreamConfiguration.Builder()
      .setResolution(settingsStoreData.width, settingsStoreData.height)
      .setLaunchRefreshRate(settingsStoreData.fps)
      .setRefreshRate(chosenFrameRate)
      .setApp(app)
      .setBitrate(settingsStoreData.bitrate * 1000)
      .setEnableSops(settingsStoreData.enableSops)
      .enableLocalAudioPlayback(settingsStoreData.playHostAudio)
      .setMaxPacketSize(1392)
      .setRemoteConfiguration(StreamConfiguration.STREAM_CFG_AUTO) // NvConnection will perform LAN and VPN detection
      .setSupportedVideoFormats(supportedVideoFormats)
      .setAttachedGamepadMask(gamepadMask.toInt())
      .setClientRefreshRateX100((displayRefreshRate * 100).toInt())
      .setAudioConfiguration(
        when (settingsStoreData.audioConfiguration) {
          AudioConfiguration.STEREO -> MoonBridge.AUDIO_CONFIGURATION_STEREO
          AudioConfiguration.SURROUND_51 -> MoonBridge.AUDIO_CONFIGURATION_51_SURROUND
          AudioConfiguration.SURROUND_71 -> MoonBridge.AUDIO_CONFIGURATION_71_SURROUND
          AudioConfiguration.UNRECOGNIZED -> MoonBridge.AUDIO_CONFIGURATION_STEREO
        }
      )
      .setColorSpace(decoderRenderer.preferredColorSpace)
      .setColorRange(decoderRenderer.preferredColorRange)
      .setPersistGamepadsAfterDisconnect(!settingsStoreData.multiController)
      .build()


    // Initialize the connection
    nvConnection = NvConnection(
      context,
      computer.activeAddress,
      computer.httpsPort, uniqueId(context), config,
      AndroidCryptoProvider(context), computer.serverCert
    )

    controllerHandler = ControllerHandler(activity, nvConnection, settingsStoreData)
    keyboardTranslator = KeyboardTranslator()
    inputCaptureListener = InputCaptureListener(
      activity,
      nvConnection,
      keyboardTranslator,
      controllerHandler,
      settingsStoreData
    ) { inputCaptureProvider }
    inputCaptureProvider = InputCaptureManager.getInputCaptureProvider(
      activity,
      streamView,
      inputCaptureListener
    )

    val inputManager = context.getSystemService(Context.INPUT_SERVICE) as InputManager
    inputManager.registerInputDeviceListener(keyboardTranslator, null)


    // Initialize touch contexts
    for (i in touchContextMap.indices) {
      if (!settingsStoreData.touchscreenTrackpad) {
        touchContextMap[i] = AbsoluteTouchContext(nvConnection, i, streamView)
      } else {
        touchContextMap[i] = RelativeTouchContext(
          nvConnection, i,
          1280, 720,
          streamView, settingsStoreData
        )
      }
    }

    if (settingsStoreData.usbDriver) {
      usbDriverServiceConnection = UsbDriverServiceConnection(controllerHandler).also {
        coroutineScope.launch {
          it.suppressPipRefCountFlow.collect { updatePipAutoEnter() }
        }
      }

      // Start the USB driver
      activity.bindService(
        Intent(activity, UsbDriverService::class.java),
        usbDriverServiceConnection!!, Service.BIND_AUTO_CREATE
      )
    }

    if (settingsStoreData.enablePip) {
      inPipModeFlow.value = activity.isInPictureInPictureMode
      pictureInPicture = BasicPictureInPicture(
        activity as ComponentActivity,
        Executors.newSingleThreadExecutor()
      ).apply {
        addOnPictureInPictureEventListener(
          ContextCompat.getMainExecutor(activity),
          object : PictureInPictureDelegate.OnPictureInPictureEventListener {
            override fun onPictureInPictureEvent(event: PictureInPictureDelegate.Event, config: Configuration?) {
              when (event) {
                PictureInPictureDelegate.Event.ENTERED -> inPipModeFlow.value = true
                PictureInPictureDelegate.Event.EXITED -> inPipModeFlow.value = false
              }
            }
          }
        )
      }
    }

    if (!decoderRenderer.isAvcSupported) {
      // If we can't find an AVC decoder, we can't proceed
      throw AvcUnsupportedException()
    }

    // The connection will be started when the surface gets created
    streamView.holder.addCallback(
      StreamSurfaceCallbacks(
        activity = activity,
        streamView = streamView,
        settingsStoreData = settingsStoreData,
        desiredRefreshRate = desiredRefreshRate,
        mayReduceRefreshRate = mayReduceRefreshRate(),
        decoderRenderer = decoderRenderer,
        nvConnection = nvConnection,
        statusFlow = statusFlow,
        notificationFlow = notificationFlow,
        coroutineScope = coroutineScope,
        controllerHandler = controllerHandler,
        errorMessage = this::errorMessage,
        updatePipAutoEnter = { updatePipAutoEnter() },
        stopConnection = { stopConnection() },
      )
    )
    statusFlow.value = Status.INITIALIZED
  }

  fun stopConnection() {
    if (statusFlow.value == Status.CONNECTED) {
      updatePipAutoEnter()
      controllerHandler.stop()
      nvConnection.stop()
    }
  }

  private fun prepareDisplayForRendering(activity: Activity, streamView: StreamView): Float {
    fun isRefreshRateEqualMatch(refreshRate: Float): Boolean {
      return refreshRate >= settingsStoreData.fps &&
          refreshRate <= settingsStoreData.fps + 3
    }

    fun isRefreshRateGoodMatch(refreshRate: Float): Boolean {
      return refreshRate >= settingsStoreData.fps &&
          refreshRate.roundToInt() % settingsStoreData.fps <= 3
    }

    val mayReduceRefreshRate =
      settingsStoreData.framePacing === FramePacing.CAP_FPS || settingsStoreData.framePacing === FramePacing.MAX_SMOOTHNESS ||
          (settingsStoreData.framePacing === FramePacing.BALANCED && settingsStoreData.reduceRefreshRate)


    val display = activity.display
    val windowLayoutParams = activity.window.attributes
    val displayRefreshRate: Float

    var bestMode = display.mode
    val nativeResolution =
      SettingsScreenUtils.getNativeResolution(activity as ComponentActivity, true)
    val isNativeResolutionStream =
      nativeResolution.width == settingsStoreData.width && nativeResolution.height == settingsStoreData.height
    var refreshRateIsGood: Boolean = isRefreshRateGoodMatch(bestMode.refreshRate)
    var refreshRateIsEqual: Boolean = isRefreshRateEqualMatch(bestMode.refreshRate)

    Timber.i(
      "Current display mode: " + bestMode.physicalWidth + "x" +
          bestMode.physicalHeight + "x" + bestMode.refreshRate
    )

    for (candidate in display.supportedModes) {
      val refreshRateReduced = candidate.refreshRate < bestMode.refreshRate
      val resolutionReduced = candidate.physicalWidth < bestMode.physicalWidth ||
          candidate.physicalHeight < bestMode.physicalHeight
      val resolutionFitsStream = candidate.physicalWidth >= settingsStoreData.width &&
          candidate.physicalHeight >= settingsStoreData.height

      Timber.i(
        "Examining display mode: " + candidate.physicalWidth + "x" +
            candidate.physicalHeight + "x" + candidate.refreshRate
      )


      if (candidate.physicalWidth > 4096 && settingsStoreData.width <= 4096) {
        // Avoid resolutions options above 4K to be safe
        continue
      }

      // On non-4K streams, we force the resolution to never change unless it's above
      // 60 FPS, which may require a resolution reduction due to HDMI bandwidth limitations,
      // or it's a native resolution stream.
      if (settingsStoreData.width < 3840 && settingsStoreData.fps <= 60 && !isNativeResolutionStream) {
        if (display.mode.physicalWidth != candidate.physicalWidth ||
          display.mode.physicalHeight != candidate.physicalHeight
        ) {
          continue
        }
      }

      // Make sure the resolution doesn't regress unless if it's over 60 FPS
      // where we may need to reduce resolution to achieve the desired refresh rate.
      if (resolutionReduced && !(settingsStoreData.fps > 60 && resolutionFitsStream)) {
        continue
      }

      if (mayReduceRefreshRate && refreshRateIsEqual && !isRefreshRateEqualMatch(candidate.refreshRate)) {
        // If we had an equal refresh rate and this one is not, skip it. In min latency
        // mode, we want to always prefer the highest frame rate even though it may cause
        // microstuttering.
        continue
      } else if (refreshRateIsGood) {
        // We've already got a good match, so if this one isn't also good, it's not
        // worth considering at all.
        if (!isRefreshRateGoodMatch(candidate.getRefreshRate())) {
          continue
        }

        if (mayReduceRefreshRate) {
          // User asked for the lowest possible refresh rate, so don't raise it if we
          // have a good match already
          if (candidate.getRefreshRate() > bestMode.getRefreshRate()) {
            continue
          }
        } else {
          // User asked for the highest possible refresh rate, so don't reduce it if we
          // have a good match already
          if (refreshRateReduced) {
            continue
          }
        }
      } else if (!isRefreshRateGoodMatch(candidate.getRefreshRate())) {
        // We didn't have a good match and this match isn't good either, so just don't
        // reduce the refresh rate.
        if (refreshRateReduced) {
          continue
        }
      } else {
        // We didn't have a good match and this match is good. Prefer this refresh rate
        // even if it reduces the refresh rate. Lowering the refresh rate can be beneficial
        // when streaming a 60 FPS stream on a 90 Hz device. We want to select 60 Hz to
        // match the frame rate even if the active display mode is 90 Hz.
      }

      bestMode = candidate
      refreshRateIsGood = isRefreshRateGoodMatch(candidate.getRefreshRate())
      refreshRateIsEqual = isRefreshRateEqualMatch(candidate.getRefreshRate())
    }

    Timber.i(
      "Best display mode: " + bestMode.physicalWidth + "x" +
          bestMode.physicalHeight + "x" + bestMode.refreshRate
    )

    // Only apply new window layout parameters if we've actually changed the display mode
    if (display.mode.modeId != bestMode.modeId) {
      // If we only changed refresh rate and we're on an OS that supports Surface.setFrameRate()
      // use that instead of using preferredDisplayModeId to avoid the possibility of triggering
      // bugs that can cause the system to switch from 4K60 to 4K24 on Chromecast 4K.
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || display.mode
          .physicalWidth != bestMode.physicalWidth || display.mode
          .physicalHeight != bestMode.physicalHeight
      ) {
        // Apply the display mode change
        windowLayoutParams.preferredDisplayModeId = bestMode.modeId
        activity.window.attributes = windowLayoutParams
      } else {
        Timber.i("Using setFrameRate() instead of preferredDisplayModeId due to matching resolution")
      }
    } else {
      Timber.i("Current display mode is already the best display mode")
    }

    displayRefreshRate = bestMode.refreshRate

    if (settingsStoreData.stretchVideo) {
      // Set the surface to the size of the video
      streamView.holder.setFixedSize(settingsStoreData.width, settingsStoreData.height)
    } else {
      // Set the surface to scale based on the aspect ratio of the stream
      streamView.setDesiredAspectRatio(settingsStoreData.width.toDouble() / settingsStoreData.height.toDouble())
    }

    // Set the desired refresh rate that will get passed into setFrameRate() later
    desiredRefreshRate = displayRefreshRate

    if (activity.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEVISION) ||
      activity.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    ) {
      // TVs may take a few moments to switch refresh rates, and we can probably assume
      // it will be eventually activated.
      // TODO: Improve this
      return displayRefreshRate
    } else {
      // Use the lower of the current refresh rate and the selected refresh rate.
      // The preferred refresh rate may not actually be applied (ex: Battery Saver mode).
      return min(display.refreshRate, displayRefreshRate)
    }
  }

  fun updatePipAutoEnter() {
    if (!settingsStoreData.enablePip) return
    val autoEnter = statusFlow.value == Status.CONNECTED && suppressPipRefCount == 0
    pictureInPicture!!.setPictureInPictureParams(getPictureInPictureParams(streamView, autoEnter))
  }

  fun getPictureInPictureParams(streamView: StreamView, autoEnter: Boolean) =
    PictureInPictureParamsCompat.Builder()
      .setAspectRatio(Rational(settingsStoreData.width, settingsStoreData.height))
      .setSourceRectHint(
        Rect(
          streamView.left, streamView.top,
          streamView.right, streamView.bottom
        )
      )
      .setEnabled(autoEnter)
      .setSeamlessResizeEnabled(true)
      .setTitle(app.appName)
      .setSubTitle(computer.name)
      .build()

  fun mayReduceRefreshRate(): Boolean {
    return settingsStoreData.framePacing === FramePacing.CAP_FPS || settingsStoreData.framePacing === FramePacing.MAX_SMOOTHNESS ||
        (settingsStoreData.framePacing === FramePacing.BALANCED && settingsStoreData.reduceRefreshRate)
  }

  fun release() {
    controllerHandler.destroy()
    val inputManager = activity.getSystemService(Context.INPUT_SERVICE) as InputManager
    inputManager.unregisterInputDeviceListener(keyboardTranslator)
    usbDriverServiceConnection?.let { if (it.connected) activity.unbindService(it) }
    inputCaptureProvider.destroy()
    pictureInPicture?.setEnabled(false)?.commit()
  }

  fun showLatencyDialog(activity: Activity) {
    val videoFormat = decoderRenderer.activeVideoFormat
    val averageEndToEndLat: Int = decoderRenderer.averageEndToEndLatency
    val averageDecoderLat: Int = decoderRenderer.averageDecoderLatency
    var message: String? = null
    if (averageEndToEndLat > 0) {
      message =
        activity.getString(R.string.s_conn_client_latency) + " " + averageEndToEndLat + " ms"
      if (averageDecoderLat > 0) {
        message += " (" + activity.getString(R.string.s_conn_client_latency_hw) + " " + averageDecoderLat + " ms)"
      }
    } else if (averageDecoderLat > 0) {
      message =
        activity.getString(R.string.s_conn_hardware_latency) + " " + averageDecoderLat + " ms"
    }


    // Add the video codec to the post-stream toast
    if (message != null) {
      message += " ["

      message += if ((videoFormat and MoonBridge.VIDEO_FORMAT_MASK_H264) !== 0) {
        "H.264"
      } else if ((videoFormat and MoonBridge.VIDEO_FORMAT_MASK_H265) !== 0) {
        "HEVC"
      } else if ((videoFormat and MoonBridge.VIDEO_FORMAT_MASK_AV1) !== 0) {
        "AV1"
      } else {
        "UNKNOWN"
      }

      if ((videoFormat and MoonBridge.VIDEO_FORMAT_MASK_10BIT) !== 0) {
        message += " HDR"
      }

      message += "]"
    }

    message?.let { Globals.commonAlertDialog.showText(it) }
  }

  class ErrorMessage(val title: String, val content: String)
  enum class Status { INITIAL, INITIALIZED, CONNECTED, TERMINATED, FAILED }
  class AvcUnsupportedException : Exception("No AVC decoder found. This device is not supported.")
}
