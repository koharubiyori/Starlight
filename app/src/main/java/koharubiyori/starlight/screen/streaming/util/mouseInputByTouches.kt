package koharubiyori.starlight.screen.streaming.util

import android.os.Build
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInteropFilter
import koharubiyori.starlight.nvstream.NvConnection
import koharubiyori.starlight.nvstream.input.MouseButtonPacket
import koharubiyori.starlight.nvstream.jni.MoonBridge
import koharubiyori.starlight.screen.streaming.streamConnection.StreamConnection
import koharubiyori.starlight.screen.streaming.util.input.capture.InputCaptureProvider
import koharubiyori.starlight.screen.streaming.util.input.touch.TouchContext
import koharubiyori.starlight.screen.streaming.util.input.virtual_controller.VirtualController
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

private const val THREE_FINGER_TAP_THRESHOLD_MS = 300L
private const val STYLUS_DOWN_DEAD_ZONE_DELAY_MS = 100L
private const val STYLUS_DOWN_DEAD_ZONE_RADIUS = 20f
private const val STYLUS_UP_DEAD_ZONE_DELAY_MS = 150L
private const val STYLUS_UP_DEAD_ZONE_RADIUS = 50f
private const val SAMSUNG_DEX_MOUSE_SOURCE = 12290

/**
 * Routes Android motion events from a Compose-hosted stream view to the active stream.
 *
 * Apply this modifier to the AndroidView that displays the stream.
 */
fun Modifier.mouseInputByTouches(
  handler: MouseInputByTouchesHandler,
  absoluteMouseMode: Boolean,
  mouseNavButtons: Boolean
): Modifier = pointerInteropFilter { event -> handler.handleMotionEvent(event, absoluteMouseMode, mouseNavButtons) }

class MouseInputByTouchesHandler(
  private val connection: StreamConnection?,
  private val inputCaptureProvider: InputCaptureProvider?,
  private val inputGrabbed: () -> Boolean,
  private val onToggleKeyboard: () -> Unit,
) {
  private var lastButtonState = 0
  private var threeFingerDownTime = 0L
  private var lastAbsTouchUpTime = 0L
  private var lastAbsTouchDownTime = 0L
  private var lastAbsTouchUpX = 0f
  private var lastAbsTouchUpY = 0f
  private var lastAbsTouchDownX = 0f
  private var lastAbsTouchDownY = 0f

  fun handleMotionEvent(
    event: MotionEvent,
    absoluteMouseMode: Boolean,
    mouseNavButtons: Boolean
  ): Boolean {
    if (!inputGrabbed()) return false
    val stream = connection ?: return false
    val streamView = stream.streamView
    val conn = stream.nvConnection
    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
      streamView.requestUnbufferedDispatch(event)
    }
    val eventSource = event.source
    val deviceSources = event.device?.sources ?: 0

    if (eventSource and InputDevice.SOURCE_CLASS_JOYSTICK != 0) {
      if (stream.controllerHandler.handleMotionEvent(event)) return true
    } else if (deviceSources and InputDevice.SOURCE_CLASS_JOYSTICK != 0 &&
      stream.controllerHandler.tryHandleTouchpadEvent(event)
    ) {
      return true
    } else if (
      eventSource and InputDevice.SOURCE_CLASS_POINTER != 0 ||
      eventSource and InputDevice.SOURCE_CLASS_POSITION != 0 ||
      eventSource == InputDevice.SOURCE_MOUSE_RELATIVE
    ) {
      val isMouseOrPen =
        eventSource == InputDevice.SOURCE_MOUSE ||
          eventSource and InputDevice.SOURCE_CLASS_POSITION != 0 ||
          eventSource == InputDevice.SOURCE_MOUSE_RELATIVE ||
          (event.pointerCount > 0 &&
            event.getToolType(0) in setOf(
              MotionEvent.TOOL_TYPE_MOUSE,
              MotionEvent.TOOL_TYPE_STYLUS,
              MotionEvent.TOOL_TYPE_ERASER,
            )) ||
          eventSource == SAMSUNG_DEX_MOUSE_SOURCE

      if (isMouseOrPen) {
        var buttonState = event.buttonState
        var changedButtons = buttonState xor lastButtonState

        if (eventSource == SAMSUNG_DEX_MOUSE_SOURCE) {
          buttonState = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> buttonState or MotionEvent.BUTTON_PRIMARY
            MotionEvent.ACTION_UP -> buttonState and MotionEvent.BUTTON_PRIMARY.inv()
            else -> buttonState or (lastButtonState and MotionEvent.BUTTON_PRIMARY)
          }
          changedButtons = buttonState xor lastButtonState
        }

        if (inputCaptureProvider?.isCapturingActive() == false) return true

        if (inputCaptureProvider?.eventHasRelativeMouseAxes(event) == true) {
          val deltaX = inputCaptureProvider.getRelativeAxisX(event).toInt().toShort()
          val deltaY = inputCaptureProvider.getRelativeAxisY(event).toInt().toShort()
          if (deltaX != 0.toShort() || deltaY != 0.toShort()) {
            if (absoluteMouseMode) {
              conn.sendMouseMoveAsMousePosition(
                deltaX,
                deltaY,
                streamView.width.toShort(),
                streamView.height.toShort(),
              )
            } else {
              conn.sendMouseMove(deltaX, deltaY)
            }
          }
        } else if (eventSource and InputDevice.SOURCE_CLASS_POSITION != 0) {
          val device = event.device
          if (device != null) {
            val xRange = device.getMotionRange(MotionEvent.AXIS_X, eventSource)
            val yRange = device.getMotionRange(MotionEvent.AXIS_Y, eventSource)
            if (xRange != null && yRange != null && xRange.min == 0f && yRange.min == 0f) {
              val xMax = xRange.max.toInt()
              val yMax = yRange.max.toInt()
              if (xMax <= Short.MAX_VALUE && yMax <= Short.MAX_VALUE) {
                conn.sendMousePosition(
                  event.x.toInt().toShort(),
                  event.y.toInt().toShort(),
                  xMax.toShort(),
                  yMax.toShort(),
                )
              }
            }
          }
        } else if (event.pointerCount > 0 && trySendPenEvent(streamView, conn, event)) {
          return true
        } else if (event.pointerCount > 0) {
          updateMousePosition(streamView, conn, event)
        }

        if (event.actionMasked == MotionEvent.ACTION_SCROLL) {
          conn.sendMouseHighResScroll((event.getAxisValue(MotionEvent.AXIS_VSCROLL) * 120).toInt().toShort())
          conn.sendMouseHighResHScroll((event.getAxisValue(MotionEvent.AXIS_HSCROLL) * 120).toInt().toShort())
        }

        sendMouseButtonChange(
          conn,
          changedButtons,
          buttonState,
          MotionEvent.BUTTON_PRIMARY,
          MouseButtonPacket.BUTTON_LEFT,
        )
        sendMouseButtonChange(
          conn,
          changedButtons,
          buttonState,
          MotionEvent.BUTTON_SECONDARY or MotionEvent.BUTTON_STYLUS_PRIMARY,
          MouseButtonPacket.BUTTON_RIGHT,
        )
        sendMouseButtonChange(
          conn,
          changedButtons,
          buttonState,
          MotionEvent.BUTTON_TERTIARY or MotionEvent.BUTTON_STYLUS_SECONDARY,
          MouseButtonPacket.BUTTON_MIDDLE,
        )

        if (mouseNavButtons) {
          sendMouseButtonChange(
            conn,
            changedButtons,
            buttonState,
            MotionEvent.BUTTON_BACK,
            MouseButtonPacket.BUTTON_X1,
          )
          sendMouseButtonChange(
            conn,
            changedButtons,
            buttonState,
            MotionEvent.BUTTON_FORWARD,
            MouseButtonPacket.BUTTON_X2,
          )
        }

        if (event.pointerCount == 1 && event.actionIndex == 0) {
          when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> when (event.getToolType(0)) {
              MotionEvent.TOOL_TYPE_STYLUS -> {
                lastAbsTouchDownTime = event.eventTime
                lastAbsTouchDownX = event.getX(0)
                lastAbsTouchDownY = event.getY(0)
                conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT)
              }
              MotionEvent.TOOL_TYPE_ERASER -> {
                lastAbsTouchDownTime = event.eventTime
                lastAbsTouchDownX = event.getX(0)
                lastAbsTouchDownY = event.getY(0)
                conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT)
              }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> when (event.getToolType(0)) {
              MotionEvent.TOOL_TYPE_STYLUS -> {
                lastAbsTouchUpTime = event.eventTime
                lastAbsTouchUpX = event.getX(0)
                lastAbsTouchUpY = event.getY(0)
                conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT)
              }
              MotionEvent.TOOL_TYPE_ERASER -> {
                lastAbsTouchUpTime = event.eventTime
                lastAbsTouchUpX = event.getX(0)
                lastAbsTouchUpY = event.getY(0)
                conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT)
              }
            }
          }
        }

        lastButtonState = buttonState
      } else {
        if (
          stream.virtualController?.controllerMode?.let {
            it != VirtualController.ControllerMode.Active
          } == true
        ) return true

        val actionIndex = event.actionIndex
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN && event.pointerCount == 3) {
          threeFingerDownTime = event.eventTime
          forEachTouchContext(stream) { it.cancelTouch() }
          return true
        }

        val context = stream.getTouchContext(actionIndex) ?: return false
        val eventX = event.getX(actionIndex).toInt()
        val eventY = event.getY(actionIndex).toInt()
        when (event.actionMasked) {
          MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_DOWN -> {
            forEachTouchContext(stream) { it.setPointerCount(event.pointerCount) }
            context.touchDownEvent(eventX, eventY, event.eventTime, true)
          }
          MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
            if (
              event.pointerCount == 1 &&
              (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                event.flags and MotionEvent.FLAG_CANCELED == 0) &&
              event.eventTime - threeFingerDownTime < THREE_FINGER_TAP_THRESHOLD_MS
            ) {
              onToggleKeyboard()
              return true
            }

            if (
              Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
              event.flags and MotionEvent.FLAG_CANCELED != 0
            ) {
              context.cancelTouch()
            } else {
              context.touchUpEvent(eventX, eventY, event.eventTime)
            }

            forEachTouchContext(stream) { it.setPointerCount(event.pointerCount - 1) }
            if (actionIndex == 0 && event.pointerCount > 1 && !context.isCancelled()) {
              context.touchDownEvent(
                event.getX(1).toInt(),
                event.getY(1).toInt(),
                event.eventTime,
                false,
              )
            }
          }
          MotionEvent.ACTION_MOVE -> {
            for (historyIndex in 0 until event.historySize) {
              forEachTouchContext(stream) { touchContext ->
                val pointerIndex = touchContext.actionIndex
                if (pointerIndex < event.pointerCount) {
                  touchContext.touchMoveEvent(
                    event.getHistoricalX(pointerIndex, historyIndex).toInt(),
                    event.getHistoricalY(pointerIndex, historyIndex).toInt(),
                    event.getHistoricalEventTime(historyIndex),
                  )
                }
              }
            }
            forEachTouchContext(stream) { touchContext ->
              val pointerIndex = touchContext.actionIndex
              if (pointerIndex < event.pointerCount) {
                touchContext.touchMoveEvent(
                  event.getX(pointerIndex).toInt(),
                  event.getY(pointerIndex).toInt(),
                  event.eventTime,
                )
              }
            }
          }
          MotionEvent.ACTION_CANCEL -> forEachTouchContext(stream) {
            it.cancelTouch()
            it.setPointerCount(0)
          }
          else -> return false
        }
      }
      return true
    }
    return false
  }

  private inline fun forEachTouchContext(stream: StreamConnection, action: (TouchContext) -> Unit) {
    for (index in 0..1) {
      stream.getTouchContext(index)?.let(action)
    }
  }

  private fun sendMouseButtonChange(
    conn: NvConnection,
    changedButtons: Int,
    buttonState: Int,
    mask: Int,
    button: Byte,
  ) {
    if (changedButtons and mask == 0) return
    if (buttonState and mask != 0) {
      conn.sendMouseButtonDown(button)
    } else {
      conn.sendMouseButtonUp(button)
    }
  }

  private fun updateMousePosition(streamView: StreamView, conn: NvConnection, event: MotionEvent) {
    var eventX = event.getX(0)
    var eventY = event.getY(0)

    if (
      event.pointerCount == 1 &&
      event.actionIndex == 0 &&
      event.getToolType(0) in setOf(MotionEvent.TOOL_TYPE_ERASER, MotionEvent.TOOL_TYPE_STYLUS)
    ) {
      when (event.actionMasked) {
        MotionEvent.ACTION_DOWN,
        MotionEvent.ACTION_HOVER_ENTER,
        MotionEvent.ACTION_HOVER_EXIT,
        MotionEvent.ACTION_HOVER_MOVE -> {
          if (
            event.eventTime - lastAbsTouchUpTime <= STYLUS_UP_DEAD_ZONE_DELAY_MS &&
            distance(eventX - lastAbsTouchUpX, eventY - lastAbsTouchUpY) <= STYLUS_UP_DEAD_ZONE_RADIUS
          ) return
        }
        MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
          if (
            event.eventTime - lastAbsTouchDownTime <= STYLUS_DOWN_DEAD_ZONE_DELAY_MS &&
            distance(eventX - lastAbsTouchDownX, eventY - lastAbsTouchDownY) <= STYLUS_DOWN_DEAD_ZONE_RADIUS
          ) return
        }
      }
    }

    eventX = eventX.coerceIn(0f, streamView.width.toFloat())
    eventY = eventY.coerceIn(0f, streamView.height.toFloat())
    conn.sendMousePosition(
      eventX.toInt().toShort(),
      eventY.toInt().toShort(),
      streamView.width.toShort(),
      streamView.height.toShort(),
    )
  }

  private fun trySendPenEvent(
    streamView: StreamView,
    conn: NvConnection,
    event: MotionEvent,
  ): Boolean {
    val eventType = eventTypeFor(event)
    if (eventType < 0) return false

    if (event.actionMasked == MotionEvent.ACTION_MOVE) {
      var handledStylusEvent = false
      for (pointerIndex in 0 until event.pointerCount) {
        val toolType = toolTypeFor(event, pointerIndex)
        if (toolType == MoonBridge.LI_TOOL_TYPE_UNKNOWN) continue
        handledStylusEvent = true
        if (!sendPenEventForPointer(streamView, conn, event, eventType, toolType, pointerIndex)) {
          return false
        }
      }
      return handledStylusEvent
    }
    if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
      return conn.sendPenEvent(
        MoonBridge.LI_TOUCH_EVENT_CANCEL_ALL,
        MoonBridge.LI_TOOL_TYPE_UNKNOWN,
        0,
        0f,
        0f,
        0f,
        0f,
        0f,
        MoonBridge.LI_ROT_UNKNOWN,
        MoonBridge.LI_TILT_UNKNOWN,
      ) != MoonBridge.LI_ERR_UNSUPPORTED
    }

    val toolType = toolTypeFor(event, event.actionIndex)
    if (toolType == MoonBridge.LI_TOOL_TYPE_UNKNOWN) return false
    return sendPenEventForPointer(
      streamView,
      conn,
      event,
      eventType,
      toolType,
      event.actionIndex,
    )
  }

  private fun sendPenEventForPointer(
    streamView: StreamView,
    conn: NvConnection,
    event: MotionEvent,
    eventType: Byte,
    toolType: Byte,
    pointerIndex: Int,
  ): Boolean {
    var penButtons: Byte = 0
    if (event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY != 0) {
      penButtons = (penButtons.toInt() or MoonBridge.LI_PEN_BUTTON_PRIMARY.toInt()).toByte()
    }
    if (event.buttonState and MotionEvent.BUTTON_STYLUS_SECONDARY != 0) {
      penButtons = (penButtons.toInt() or MoonBridge.LI_PEN_BUTTON_SECONDARY.toInt()).toByte()
    }

    val device = event.device
    val tilt = if (device?.getMotionRange(MotionEvent.AXIS_TILT, event.source) != null) {
      Math.toDegrees(event.getAxisValue(MotionEvent.AXIS_TILT, pointerIndex).toDouble()).toInt().toByte()
    } else {
      MoonBridge.LI_TILT_UNKNOWN
    }
    val coordinates = normalizedCoordinates(streamView, event, pointerIndex)
    val contactArea = normalizedContactArea(streamView, event, pointerIndex)
    return conn.sendPenEvent(
      eventType,
      toolType,
      penButtons,
      coordinates.first,
      coordinates.second,
      pressureOrDistance(event, pointerIndex),
      contactArea.first,
      contactArea.second,
      rotationDegrees(event, pointerIndex),
      tilt,
    ) != MoonBridge.LI_ERR_UNSUPPORTED
  }

  private fun normalizedCoordinates(
    streamView: StreamView,
    event: MotionEvent,
    pointerIndex: Int,
  ): Pair<Float, Float> {
    val width = streamView.width
    val height = streamView.height
    if (width <= 0 || height <= 0) return 0f to 0f
    val x = event.getX(pointerIndex).coerceIn(0f, width.toFloat()) / width
    val y = event.getY(pointerIndex).coerceIn(0f, height.toFloat()) / height
    return x to y
  }

  private fun normalizedContactArea(
    streamView: StreamView,
    event: MotionEvent,
    pointerIndex: Int,
  ): Pair<Float, Float> {
    if (streamView.width <= 0 || streamView.height <= 0) return 0f to 0f
    val device = event.device
    val orientation = if (
      device == null || device.getMotionRange(MotionEvent.AXIS_ORIENTATION, event.source) == null
    ) {
      Math.PI.toFloat() / 4f
    } else {
      event.getOrientation(pointerIndex)
    }
    val major: Float
    val minor: Float
    when (event.actionMasked) {
      MotionEvent.ACTION_HOVER_ENTER,
      MotionEvent.ACTION_HOVER_MOVE,
      MotionEvent.ACTION_HOVER_EXIT -> {
        major = event.getToolMajor(pointerIndex)
        minor = event.getToolMinor(pointerIndex)
      }
      else -> {
        major = event.getTouchMajor(pointerIndex)
        minor = event.getTouchMinor(pointerIndex)
      }
    }

    fun cartesianRadius(radius: Float, angle: Float): Float {
      val x = min(abs(radius * cos(angle)), streamView.width.toFloat()) / streamView.width
      val y = min(abs(radius * sin(angle)), streamView.height.toFloat()) / streamView.height
      return sqrt(x * x + y * y)
    }

    return cartesianRadius(major, orientation) to
      cartesianRadius(minor, orientation + Math.PI.toFloat() / 2f)
  }

  private fun pressureOrDistance(event: MotionEvent, pointerIndex: Int): Float {
    if (event.actionMasked in setOf(
        MotionEvent.ACTION_HOVER_ENTER,
        MotionEvent.ACTION_HOVER_MOVE,
        MotionEvent.ACTION_HOVER_EXIT,
      )
    ) {
      val range = event.device?.getMotionRange(MotionEvent.AXIS_DISTANCE, event.source)
        ?: return 0f
      return (event.getAxisValue(MotionEvent.AXIS_DISTANCE, pointerIndex) - range.min) / range.range
    }
    return event.getPressure(pointerIndex)
  }

  private fun rotationDegrees(event: MotionEvent, pointerIndex: Int): Short {
    val range = event.device?.getMotionRange(MotionEvent.AXIS_ORIENTATION, event.source)
      ?: return MoonBridge.LI_ROT_UNKNOWN
    if (range.range == 0f) return MoonBridge.LI_ROT_UNKNOWN
    var degrees = Math.toDegrees(event.getOrientation(pointerIndex).toDouble()).toInt()
    if (degrees < 0) degrees += 360
    return degrees.toShort()
  }

  private fun eventTypeFor(event: MotionEvent): Byte = when (event.actionMasked) {
    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> MoonBridge.LI_TOUCH_EVENT_DOWN
    MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP ->
      if (event.flags and MotionEvent.FLAG_CANCELED != 0) {
        MoonBridge.LI_TOUCH_EVENT_CANCEL
      } else {
        MoonBridge.LI_TOUCH_EVENT_UP
      }
    MotionEvent.ACTION_MOVE -> MoonBridge.LI_TOUCH_EVENT_MOVE
    MotionEvent.ACTION_CANCEL -> MoonBridge.LI_TOUCH_EVENT_CANCEL_ALL
    MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> MoonBridge.LI_TOUCH_EVENT_HOVER
    MotionEvent.ACTION_HOVER_EXIT -> MoonBridge.LI_TOUCH_EVENT_HOVER_LEAVE
    MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> MoonBridge.LI_TOUCH_EVENT_BUTTON_ONLY
    else -> -1
  }

  private fun toolTypeFor(event: MotionEvent, pointerIndex: Int): Byte =
    when (event.getToolType(pointerIndex)) {
      MotionEvent.TOOL_TYPE_ERASER -> MoonBridge.LI_TOOL_TYPE_ERASER
      MotionEvent.TOOL_TYPE_STYLUS -> MoonBridge.LI_TOOL_TYPE_PEN
      else -> MoonBridge.LI_TOOL_TYPE_UNKNOWN
    }

  private fun distance(x: Float, y: Float): Float = sqrt(x * x + y * y)
}
