package koharubiyori.starlight.screen.streaming.streamConnection

import android.app.Activity
import android.os.Build
import android.view.Surface
import android.view.SurfaceHolder
import koharubiyori.starlight.nvstream.NvConnection
import koharubiyori.starlight.screen.streaming.util.audio.AndroidAudioRenderer
import koharubiyori.starlight.screen.streaming.util.input.ControllerHandler
import koharubiyori.starlight.screen.streaming.util.video.MediaCodecDecoderRenderer
import koharubiyori.starlight.store.proto.SettingsStoreData
import koharubiyori.starlight.screen.streaming.util.StreamView
import koharubiyori.starlight.util.globalIoContinueScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import kotlin.reflect.KMutableProperty0

class StreamSurfaceCallbacks(
  private val activity: Activity,
  private val streamView: StreamView,
  private val settingsStoreData: SettingsStoreData,
  private val desiredRefreshRate: Float,
  private val mayReduceRefreshRate: Boolean,
  private val decoderRenderer: MediaCodecDecoderRenderer,
  private val nvConnection: NvConnection,
  private val statusFlow: MutableStateFlow<StreamConnection.Status>,
  private val notificationFlow: MutableSharedFlow<String>,
  private val coroutineScope: CoroutineScope,
  private val controllerHandler: ControllerHandler,
  private val errorMessage: KMutableProperty0<StreamConnection.ErrorMessage?>,
  private val updatePipAutoEnter: () -> Unit,
  private val stopConnection: () -> Unit,
) : SurfaceHolder.Callback {
  private var surfaceCreated = false
  private var attemptedConnection = false

  override fun surfaceCreated(holder: SurfaceHolder) {
    Timber.i("Surface created")
    runBlocking {
      surfaceCreated = true

      // Android will pick the lowest matching refresh rate for a given frame rate value, so we want
      // to report the true FPS value if refresh rate reduction is enabled. We also report the true
      // FPS value if there's no suitable matching refresh rate. In that case, Android could try to
      // select a lower refresh rate that avoids uneven pull-down (ex: 30 Hz for a 60 FPS stream on
      // a display that maxes out at 50 Hz).
      val desiredFrameRate =
        if (mayReduceRefreshRate || desiredRefreshRate < settingsStoreData.fps) {
          settingsStoreData.fps.toFloat()
        } else {
          // Otherwise, we will pretend that our frame rate matches the refresh rate we picked in
          // prepareDisplayForRendering(). This will usually be the highest refresh rate that our
          // frame rate evenly divides into, which ensures the lowest possible display latency.
          desiredRefreshRate
        }

      // Tell the OS about our frame rate to allow it to adapt the display refresh rate appropriately
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        // We want to change frame rate even if it's not seamless, since prepareDisplayForRendering()
        // will not set the display mode on S+ if it only differs by the refresh rate. It depends
        // on us to trigger the frame rate switch here.
        holder.surface.setFrameRate(
          desiredFrameRate,
          Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
          Surface.CHANGE_FRAME_RATE_ALWAYS
        )
      }
    }
  }

  override fun surfaceChanged(
    holder: SurfaceHolder,
    format: Int,
    width: Int,
    height: Int
  ) {
    Timber.i("Surface changed")
    runBlocking {
      check(surfaceCreated) { "Surface changed before creation!" }
      if (!attemptedConnection) {
        attemptedConnection = true

        decoderRenderer.setRenderTarget(holder)
        nvConnection.start(
          AndroidAudioRenderer(activity.applicationContext, settingsStoreData.enableAudioFx),
          decoderRenderer,
          StreamConnectionListener(
            activity = activity,
            streamView = streamView,
            coroutineScope = coroutineScope,
            controllerHandler = controllerHandler,
            decoderRenderer = decoderRenderer,
            statusFlow = statusFlow,
            notificationFlow = notificationFlow,
            errorMessage = errorMessage,
            updatePipAutoEnter = updatePipAutoEnter,
            stopConnection = stopConnection,
          )
        )
      }
    }
  }

  override fun surfaceDestroyed(holder: SurfaceHolder) {
    Timber.i("Surface destroyed")
    check(surfaceCreated) { "Surface destroyed before creation!" }

    if (attemptedConnection) {
      // Let the decoder know immediately that the surface is gone
      decoderRenderer.prepareForStop()

      if (statusFlow.value == StreamConnection.Status.CONNECTED) {
        globalIoContinueScope.launch {
          stopConnection()
        }
      }
    }
  }
}
