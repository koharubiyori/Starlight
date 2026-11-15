package koharubiyori.starlight.screen.streaming.streamConnection

import android.content.ComponentName
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.os.Build
import android.view.KeyEvent
import android.view.WindowManager
import koharubiyori.starlight.nvstream.input.KeyboardPacket
import koharubiyori.starlight.nvstream.input.MouseButtonPacket
import android.app.Activity
import koharubiyori.starlight.nvstream.NvConnection
import koharubiyori.starlight.nvstream.jni.MoonBridge
import koharubiyori.starlight.screen.streaming.util.input.ControllerHandler
import koharubiyori.starlight.screen.streaming.util.input.KeyboardTranslator
import koharubiyori.starlight.screen.streaming.util.input.capture.InputCaptureProvider
import koharubiyori.starlight.screen.streaming.util.input.evdev.EvdevListener
import koharubiyori.starlight.store.proto.SettingsStoreData
import koharubiyori.starlight.util.debugPrint
import timber.log.Timber
import java.lang.reflect.InvocationTargetException
import kotlin.experimental.and
import kotlin.experimental.inv
import kotlin.experimental.or

class InputCaptureListener(
  private val activity: Activity,
  private val nvConnection: NvConnection,
  private val keyboardTranslator: KeyboardTranslator,
  private val controllerHandler: ControllerHandler,
  private val settingsStoreData: SettingsStoreData,
  private val getInputCaptureProvider: () -> InputCaptureProvider,
) : EvdevListener {
  private val inputCaptureProvider: InputCaptureProvider
    get() = getInputCaptureProvider()

  private var modifierFlags = 0
  private var waitingForAllModifiersUp = false
  private var cursorVisible = false
  private var grabbedInput = true
  private var specialKeyCode = KeyEvent.KEYCODE_UNKNOWN

  override fun mouseMove(deltaX: Int, deltaY: Int) {
    nvConnection.sendMouseMove(deltaX.toShort(), deltaY.toShort())
  }

  override fun mouseButtonEvent(buttonId: Int, down: Boolean) {
    val buttonIndex = when (buttonId) {
      EvdevListener.BUTTON_LEFT -> MouseButtonPacket.BUTTON_LEFT
      EvdevListener.BUTTON_MIDDLE -> MouseButtonPacket.BUTTON_MIDDLE
      EvdevListener.BUTTON_RIGHT -> MouseButtonPacket.BUTTON_RIGHT
      EvdevListener.BUTTON_X1 -> MouseButtonPacket.BUTTON_X1
      EvdevListener.BUTTON_X2 -> MouseButtonPacket.BUTTON_X2
      else -> {
        Timber.i("Unhandled button: %s", buttonId)
        return
      }
    }

    if (down) {
      nvConnection.sendMouseButtonDown(buttonIndex)
    } else {
      nvConnection.sendMouseButtonUp(buttonIndex)
    }
  }

  override fun mouseVScroll(amount: Byte) {
    nvConnection.sendMouseScroll(amount)
  }

  override fun mouseHScroll(amount: Byte) {
    nvConnection.sendMouseHScroll(amount)
  }

  /**
   * @param keyCode [KeyEvent]
   * @param modifier [KeyboardPacket]
  * */
  fun sendPairedKeyInput(keyCode: Int, modifier: Byte = 0) {
    val keyMap = keyboardTranslator.translate(keyCode, -1)
    if (keyMap.toInt() == 0) return

    val flags = MoonBridge.SS_KBE_FLAG_NON_NORMALIZED
    var activeModifiers = getModifierState()
    val injectedModifiers = mutableListOf<Pair<Byte, Short>>()
    val modifierKeys = listOf(
      KeyboardPacket.MODIFIER_CTRL to KeyEvent.KEYCODE_CTRL_LEFT,
      KeyboardPacket.MODIFIER_SHIFT to KeyEvent.KEYCODE_SHIFT_LEFT,
      KeyboardPacket.MODIFIER_ALT to KeyEvent.KEYCODE_ALT_LEFT,
      KeyboardPacket.MODIFIER_META to KeyEvent.KEYCODE_META_LEFT,
    )

    for ((modifierBit, modifierKeyCode) in modifierKeys) {
      if (
        modifier and modifierBit != 0.toByte() &&
        activeModifiers and modifierBit == 0.toByte()
      ) {
        val modifierKeyMap = keyboardTranslator.translate(modifierKeyCode, -1)
        if (modifierKeyMap.toInt() == 0) continue

        activeModifiers = activeModifiers or modifierBit
        nvConnection.sendKeyboardInput(
          modifierKeyMap,
          KeyboardPacket.KEY_DOWN,
          activeModifiers,
          flags
        )
        injectedModifiers += modifierBit to modifierKeyMap
      }
    }

    val effectiveModifier = applyKeySpecificModifiers(keyCode, activeModifiers or modifier)
    nvConnection.sendKeyboardInput(keyMap, KeyboardPacket.KEY_DOWN, effectiveModifier, flags)
    nvConnection.sendKeyboardInput(keyMap, KeyboardPacket.KEY_UP, effectiveModifier, flags)

    for ((modifierBit, modifierKeyMap) in injectedModifiers.asReversed()) {
      activeModifiers = activeModifiers and modifierBit.inv()
      nvConnection.sendKeyboardInput(
        modifierKeyMap,
        KeyboardPacket.KEY_UP,
        activeModifiers,
        flags
      )
    }
  }

  override fun keyboardEvent(buttonDown: Boolean, keyCode: Short) {
    val keyMap = keyboardTranslator.translate(keyCode.toInt(), -1)
    if (keyMap.toInt() != 0) {
      // handleSpecialKeys() takes the Android keycode
      if (handleSpecialKeys(keyCode.toInt(), buttonDown)) {
        return
      }

      if (buttonDown) {
        nvConnection.sendKeyboardInput(
          keyMap,
          KeyboardPacket.KEY_DOWN,
          getModifierState(keyCode.toInt()),
          0.toByte()
        )
      } else {
        nvConnection.sendKeyboardInput(
          keyMap,
          KeyboardPacket.KEY_UP,
          getModifierState(keyCode.toInt()),
          0.toByte()
        )
      }
    }
  }

  fun handleKeyDown(event: KeyEvent): Boolean {
    if ((event.flags and KeyEvent.FLAG_VIRTUAL_HARD_KEY) != 0) return false

    if (isMouseBackEvent(event)) {
      if (!settingsStoreData.mouseNavButtons) {
        nvConnection.sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT)
      }
      return true
    }

    if (ControllerHandler.isGameControllerDevice(event.device) &&
      controllerHandler.handleButtonDown(event)
    ) {
      return true
    }

    if (handleSpecialKeys(event.keyCode, true)) return true
    if (!grabbedInput) return false

    val translated = keyboardTranslator.translate(event.keyCode, event.deviceId)
    if (translated.toInt() == 0) {
      val unicodeChar = event.unicodeChar
      if (unicodeChar and KeyCharacterMap.COMBINING_ACCENT == 0 &&
        unicodeChar and KeyCharacterMap.COMBINING_ACCENT_MASK != 0
      ) {
        nvConnection.sendUtf8Text("" + unicodeChar.toChar())
        return true
      }
      return false
    }

    if (event.repeatCount > 0) return true

    nvConnection.sendKeyboardInput(
      translated,
      KeyboardPacket.KEY_DOWN,
      getModifierState(event),
      if (keyboardTranslator.hasNormalizedMapping(event.keyCode, event.deviceId)) {
        0
      } else {
        MoonBridge.SS_KBE_FLAG_NON_NORMALIZED
      }
    )
    return true
  }

  fun handleKeyUp(event: KeyEvent): Boolean {
    if ((event.flags and KeyEvent.FLAG_VIRTUAL_HARD_KEY) != 0) return false

    if (isMouseBackEvent(event)) {
      if (!settingsStoreData.mouseNavButtons) {
        nvConnection.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT)
      }
      return true
    }

    if (ControllerHandler.isGameControllerDevice(event.device) &&
      controllerHandler.handleButtonUp(event)
    ) {
      return true
    }

    if (handleSpecialKeys(event.keyCode, false)) return true
    if (!grabbedInput) return false

    val translated = keyboardTranslator.translate(event.keyCode, event.deviceId)
    if (translated.toInt() == 0) {
      val unicodeChar = event.unicodeChar
      return unicodeChar and KeyCharacterMap.COMBINING_ACCENT == 0 &&
        unicodeChar and KeyCharacterMap.COMBINING_ACCENT_MASK != 0
    }

    nvConnection.sendKeyboardInput(
      translated,
      KeyboardPacket.KEY_UP,
      getModifierState(event),
      if (keyboardTranslator.hasNormalizedMapping(event.keyCode, event.deviceId)) {
        0
      } else {
        MoonBridge.SS_KBE_FLAG_NON_NORMALIZED
      }
    )
    return true
  }

  fun handleKeyMultiple(event: KeyEvent): Boolean {
    if (event.keyCode != KeyEvent.KEYCODE_UNKNOWN || event.characters == null) return false
    nvConnection.sendUtf8Text(event.characters)
    return true
  }

  private fun isMouseBackEvent(event: KeyEvent): Boolean {
    return (event.source == InputDevice.SOURCE_MOUSE ||
      event.source == InputDevice.SOURCE_MOUSE_RELATIVE) &&
      event.keyCode == KeyEvent.KEYCODE_BACK
  }

  private fun setInputGrabState(grab: Boolean) {
    // Grab/ungrab the mouse cursor
    if (grab) {
      inputCaptureProvider.enableCapture()

      // Enabling capture may hide the cursor again, so
      // we will need to show it again.
      if (cursorVisible) {
        inputCaptureProvider.showCursor()
      }
    } else {
      inputCaptureProvider.disableCapture()
    }

    // Grab/ungrab system keyboard shortcuts
    setMetaKeyCaptureState(grab)

    grabbedInput = grab
  }

  private val toggleGrab: Runnable = object : Runnable {
    override fun run() {
      setInputGrabState(!grabbedInput)
    }
  }

  // Returns true if the key stroke was consumed
  private fun handleSpecialKeys(androidKeyCode: Int, down: Boolean): Boolean {
    var nonModifierKeyCode = KeyEvent.KEYCODE_UNKNOWN

    var modifierMask: Byte = 0
    if (androidKeyCode == KeyEvent.KEYCODE_CTRL_LEFT ||
      androidKeyCode == KeyEvent.KEYCODE_CTRL_RIGHT
    ) {
      modifierMask = KeyboardPacket.MODIFIER_CTRL
    } else if (androidKeyCode == KeyEvent.KEYCODE_SHIFT_LEFT ||
      androidKeyCode == KeyEvent.KEYCODE_SHIFT_RIGHT
    ) {
      modifierMask = KeyboardPacket.MODIFIER_SHIFT
    } else if (androidKeyCode == KeyEvent.KEYCODE_ALT_LEFT ||
      androidKeyCode == KeyEvent.KEYCODE_ALT_RIGHT
    ) {
      modifierMask = KeyboardPacket.MODIFIER_ALT
    } else if (androidKeyCode == KeyEvent.KEYCODE_META_LEFT ||
      androidKeyCode == KeyEvent.KEYCODE_META_RIGHT
    ) {
      modifierMask = KeyboardPacket.MODIFIER_META
    } else {
      nonModifierKeyCode = androidKeyCode
    }

    if (down) {
      this.modifierFlags = this.modifierFlags or modifierMask.toInt()
    } else {
      this.modifierFlags = this.modifierFlags and modifierMask.inv().toInt()
    }

    // Handle the special combos on the key up
    if (waitingForAllModifiersUp || specialKeyCode != KeyEvent.KEYCODE_UNKNOWN) {
      if (specialKeyCode == androidKeyCode) {
        // If this is a key up for the special key itself, eat that because the host never saw the original key down
        return true
      } else if (modifierFlags != 0) {
        // While we're waiting for modifiers to come up, eat all key downs and allow all key ups to pass
        return down
      } else {
        // When all modifiers are up, perform the special action
        when (specialKeyCode) {
          KeyEvent.KEYCODE_Z -> {
            activity.window.decorView.handler?.postDelayed(toggleGrab, 250)
          }

          KeyEvent.KEYCODE_Q -> activity.finish()

          KeyEvent.KEYCODE_C -> {
            if (!grabbedInput) {
              inputCaptureProvider.enableCapture()
              grabbedInput = true
            }
            cursorVisible = !cursorVisible
            if (cursorVisible) {
              inputCaptureProvider.showCursor()
            } else {
              inputCaptureProvider.hideCursor()
            }
          }

          else -> {}
        }

        // Reset special key state
        specialKeyCode = KeyEvent.KEYCODE_UNKNOWN
        waitingForAllModifiersUp = false
      }
    } else if ((modifierFlags and (KeyboardPacket.MODIFIER_CTRL.toInt() or KeyboardPacket.MODIFIER_ALT.toInt() or KeyboardPacket.MODIFIER_SHIFT.toInt())) ==
      (KeyboardPacket.MODIFIER_CTRL.toInt() or KeyboardPacket.MODIFIER_ALT.toInt() or KeyboardPacket.MODIFIER_SHIFT.toInt()) &&
      (down && nonModifierKeyCode != KeyEvent.KEYCODE_UNKNOWN)
    ) {
      when (androidKeyCode) {
        KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_Q, KeyEvent.KEYCODE_C -> {
          // Remember that a special key combo was activated, so we can consume all key
          // events until the modifiers come up
          specialKeyCode = androidKeyCode
          waitingForAllModifiersUp = true
          return true
        }

        else ->                     // This isn't a special combo that we consume on the client side
          return false
      }
    }

    // Not a special combo
    return false
  }

  private fun getModifierState(): Byte {
    return modifierFlags.toByte()
  }

  private fun getModifierState(event: KeyEvent): Byte {
    // Start with the global modifier state to ensure we cover the case
    // detailed in https://github.com/moonlight-stream/moonlight-android/issues/840
    var modifier: Byte = getModifierState()
    if (event.isShiftPressed) {
      modifier = modifier or KeyboardPacket.MODIFIER_SHIFT
    }
    if (event.isCtrlPressed) {
      modifier = modifier or KeyboardPacket.MODIFIER_CTRL
    }
    if (event.isAltPressed) {
      modifier = modifier or KeyboardPacket.MODIFIER_ALT
    }
    if (event.isMetaPressed) {
      modifier = modifier or KeyboardPacket.MODIFIER_META
    }
    return applyKeySpecificModifiers(event.getKeyCode(), modifier)
  }

  private fun getModifierState(keyCode: Int): Byte {
    return applyKeySpecificModifiers(keyCode, getModifierState())
  }

  private fun applyKeySpecificModifiers(keyCode: Int, modifier: Byte): Byte {
    var modifier = modifier
    if (keyCode == KeyEvent.KEYCODE_PLUS) {
      // The host protocol has a single US =/+ virtual key, so Android's semantic plus key needs Shift.
      modifier = modifier or KeyboardPacket.MODIFIER_SHIFT
    }
    return modifier
  }

  private fun setMetaKeyCaptureState(enabled: Boolean) {
    // Android has native keyboard capture support starting in API 36.1
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA && Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1) {
      val windowLayoutParams: WindowManager.LayoutParams = activity.window.attributes
      windowLayoutParams.setKeyboardCaptureEnabled(enabled)
      activity.window.attributes = windowLayoutParams
    } else {
      // This uses custom APIs present on some Samsung devices to allow capture of
      // meta key events while streaming.
      try {
        val semWindowManager = Class.forName("com.samsung.android.view.SemWindowManager")
        val getInstanceMethod = semWindowManager.getMethod("getInstance")
        val manager = getInstanceMethod.invoke(null)

        if (manager != null) {
          val parameterTypes = arrayOfNulls<Class<*>>(2)
          parameterTypes[0] = ComponentName::class.java
          parameterTypes[1] = Boolean::class.javaPrimitiveType
          val requestMetaKeyEventMethod =
            semWindowManager.getDeclaredMethod("requestMetaKeyEvent", *parameterTypes)
          requestMetaKeyEventMethod.invoke(manager, activity.componentName, enabled)
        } else {
          Timber.w("SemWindowManager.getInstance() returned null")
        }
      } catch (ignored: ClassNotFoundException) {
      } catch (e: NoSuchMethodException) {
        e.printStackTrace()
      } catch (e: InvocationTargetException) {
        e.printStackTrace()
      } catch (e: IllegalAccessException) {
        e.printStackTrace()
      }
    }
  }
}
