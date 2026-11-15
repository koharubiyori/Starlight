package koharubiyori.starlight.screen.streaming.util

import android.view.KeyEvent
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import koharubiyori.starlight.screen.streaming.streamConnection.InputCaptureListener

fun Modifier.keyboardInput(listener: InputCaptureListener?): Modifier =
  onPreviewKeyEvent { composeEvent ->
    val event = composeEvent.nativeKeyEvent
    if (listOf(
        KeyEvent.KEYCODE_VOLUME_UP,
        KeyEvent.KEYCODE_VOLUME_DOWN,
        KeyEvent.KEYCODE_VOLUME_MUTE
      ).contains(event.keyCode)
    ) return@onPreviewKeyEvent false

    when (event.action) {
      KeyEvent.ACTION_DOWN -> listener?.handleKeyDown(event) ?: false
      KeyEvent.ACTION_UP -> listener?.handleKeyUp(event) ?: false
      KeyEvent.ACTION_MULTIPLE -> listener?.handleKeyMultiple(event) ?: false
      else -> false
    }
  }
