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
  private val onCreated: () -> Unit,
  private val onReady: () -> Unit,
  private val onDestroyed: () -> Unit,
) : SurfaceHolder.Callback {
  private var surfaceCreated = false
  private var attemptedConnection = false

  override fun surfaceCreated(holder: SurfaceHolder) {
    Timber.i("Surface created")
    surfaceCreated = true
    onCreated()
  }

  override fun surfaceChanged(
    holder: SurfaceHolder,
    format: Int,
    width: Int,
    height: Int
  ) {
    Timber.i("Surface changed")
    check(surfaceCreated) { "Surface changed before creation!" }
    if (!attemptedConnection) {
      attemptedConnection = true
      onReady()
    }
  }

  override fun surfaceDestroyed(holder: SurfaceHolder) {
    Timber.i("Surface destroyed")
    check(surfaceCreated) { "Surface destroyed before creation!" }

    if (attemptedConnection) onDestroyed()
  }
}
