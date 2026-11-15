package koharubiyori.starlight.screen.streaming.streamConnection

import android.app.Activity
import android.view.WindowManager
import koharubiyori.starlight.CONNECTION_TEST_SERVER
import koharubiyori.starlight.R
import koharubiyori.starlight.nvstream.NvConnectionListener
import koharubiyori.starlight.nvstream.jni.MoonBridge
import koharubiyori.starlight.screen.streaming.util.StreamView
import koharubiyori.starlight.screen.streaming.util.input.ControllerHandler
import koharubiyori.starlight.screen.streaming.util.video.MediaCodecDecoderRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.reflect.KMutableProperty0
import kotlin.math.abs

class StreamConnectionListener(
  private val activity: Activity,
  private val streamView: StreamView,
  private val coroutineScope: CoroutineScope,
  private val controllerHandler: ControllerHandler,
  private val decoderRenderer: MediaCodecDecoderRenderer,
  private val updatePipAutoEnter: () -> Unit,
  private val stopConnection: () -> Unit,
  private val statusFlow: MutableStateFlow<StreamConnection.Status>,
  private val notificationFlow: MutableSharedFlow<String>,
  private val errorMessage: KMutableProperty0<StreamConnection.ErrorMessage?>,
) : NvConnectionListener {

  override fun stageStarting(stage: String?) {}
  override fun stageComplete(stage: String?) {}

  override fun stageFailed(stage: String?, portFlags: Int, errorCode: Int) {
    Timber.e("[Stream connection] %s failed: %s", stage, errorCode)
    statusFlow.value = StreamConnection.Status.FAILED

    coroutineScope.launch {
      val portTestResult = withContext(Dispatchers.IO) {
        MoonBridge.testClientConnectivity(CONNECTION_TEST_SERVER, 443, portFlags)
      }

      withContext(Dispatchers.Main) {
        var errorMsgContent = ""
        if (stage!!.contains("video") && streamView.holder.surface.isValid) {
          errorMsgContent = activity.getString(R.string.s_video_decoder_init_failed)
        } else {
          errorMsgContent =
            activity.getString(R.string.s_conn_error_msg) + " " + stage + " (error " + errorCode + ")"

          if (portFlags != 0) {
            errorMsgContent += "\n\n" + activity.getString(R.string.s_check_ports_msg) + "\n" +
                MoonBridge.stringifyPortFlags(portFlags, "\n")
          }

          if (portTestResult != MoonBridge.ML_TEST_RESULT_INCONCLUSIVE && portTestResult != 0) {
            errorMsgContent += "\n\n" + activity.getString(R.string.s_nettest_text_blocked)
          }
        }

        errorMessage.set(
          StreamConnection.ErrorMessage(
            activity.getString(R.string.connection_error),
            errorMsgContent
          )
        )
      }
    }
  }

  override fun connectionStarted() {
    Timber.i("[Stream connection] Stream connection Started!")
    statusFlow.value = StreamConnection.Status.CONNECTED
    coroutineScope.launch(Dispatchers.Main) {
      // Keep the display on
      activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

      updatePipAutoEnter()
    }
  }

  override fun connectionTerminated(errorCode: Int) {
    if (errorCode == MoonBridge.ML_ERROR_GRACEFUL_TERMINATION) {
      statusFlow.value = StreamConnection.Status.TERMINATED
      return
    }

    Timber.e("[Stream connection] Connection terminated with error code: $errorCode")

    coroutineScope.launch {
      val portFlags = withContext(Dispatchers.IO) {
        MoonBridge.getPortFlagsFromTerminationErrorCode(errorCode)
      }
      val portTestResult = withContext(Dispatchers.IO) {
        MoonBridge.testClientConnectivity(CONNECTION_TEST_SERVER, 443, portFlags)
      }

      withContext(Dispatchers.Main) {
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        controllerHandler.stop()
        stopConnection()

        var errorMsgContent: String
        if (errorCode != MoonBridge.ML_ERROR_GRACEFUL_TERMINATION) {
          if (portTestResult != MoonBridge.ML_TEST_RESULT_INCONCLUSIVE && portTestResult != 0) {
            // If we got a blocked result, that supersedes any other error message
            errorMsgContent = activity.getString(R.string.s_nettest_text_blocked)
          } else {
            when (errorCode) {
              MoonBridge.ML_ERROR_NO_VIDEO_TRAFFIC -> errorMsgContent =
                activity.getString(R.string.s_no_video_received_error)

              MoonBridge.ML_ERROR_NO_VIDEO_FRAME -> errorMsgContent =
                activity.getString(R.string.s_no_frame_received_error)

              MoonBridge.ML_ERROR_UNEXPECTED_EARLY_TERMINATION, MoonBridge.ML_ERROR_PROTECTED_CONTENT -> errorMsgContent =
                activity.getString(R.string.s_early_termination_error)

              MoonBridge.ML_ERROR_FRAME_CONVERSION -> errorMsgContent =
                activity.getString(R.string.s_frame_conversion_error)

              else -> {
                // We'll assume large errors are hex values
                val errorCodeString: String? = if (abs(errorCode) > 1000) {
                  Integer.toHexString(errorCode)
                } else {
                  errorCode.toString()
                }
                errorMsgContent = activity.getString(R.string.s_conn_terminated_msg) + "\n\n" +
                    activity.getString(R.string.s_error_code_prefix) + " " + errorCodeString
              }
            }
          }

          if (portFlags != 0) {
            errorMsgContent += "\n\n" + activity.getString(R.string.s_check_ports_msg) + "\n" +
                MoonBridge.stringifyPortFlags(portFlags, "\n")
          }

          errorMessage.set(
            StreamConnection.ErrorMessage(
              activity.getString(R.string.connection_terminated),
              errorMsgContent
            )
          )

          statusFlow.value = StreamConnection.Status.FAILED
        }
      }
    }
  }

  override fun connectionStatusUpdate(connectionStatus: Int) {}

  override fun displayMessage(message: String?) {
    message?.let { notificationFlow.tryEmit(it) }
  }

  override fun displayTransientMessage(message: String?) {
    message?.let { notificationFlow.tryEmit(it) }
  }

  override fun rumble(controllerNumber: Short, lowFreqMotor: Short, highFreqMotor: Short) {
    Timber.i("Rumble on gamepad %d: %04x %04x", controllerNumber, lowFreqMotor, highFreqMotor)
    controllerHandler.handleRumble(controllerNumber, lowFreqMotor, highFreqMotor)
  }

  override fun rumbleTriggers(controllerNumber: Short, leftTrigger: Short, rightTrigger: Short) {
    Timber.i("Rumble on gamepad triggers %d: %04x %04x", controllerNumber, leftTrigger, rightTrigger)
    controllerHandler.handleRumbleTriggers(controllerNumber, leftTrigger, rightTrigger)
  }

  override fun setHdrMode(enabled: Boolean, hdrMetadata: ByteArray?) {
    Timber.i("Display HDR mode: " + (if (enabled) "enabled" else "disabled"))
    decoderRenderer.setHdrMode(enabled, hdrMetadata)
  }

  override fun setMotionEventState(controllerNumber: Short, motionType: Byte, reportRateHz: Short) {
    controllerHandler.handleSetMotionEventState(controllerNumber, motionType, reportRateHz)
  }

  override fun setControllerLED(controllerNumber: Short, r: Byte, g: Byte, b: Byte) {
    controllerHandler.handleSetControllerLED(controllerNumber, r, g, b)
  }
}
