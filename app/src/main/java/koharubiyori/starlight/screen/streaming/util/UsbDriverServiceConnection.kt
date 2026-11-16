package koharubiyori.starlight.screen.streaming.util

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import koharubiyori.starlight.screen.streaming.util.input.driver.UsbDriverListener
import koharubiyori.starlight.screen.streaming.util.input.driver.UsbDriverService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

class UsbDriverServiceConnection(val controllerHandler: UsbDriverListener) : ServiceConnection {
  private val _usbPermissionPromptCountFlow = MutableStateFlow(0)
  val usbPermissionPromptCountFlow = _usbPermissionPromptCountFlow.asStateFlow()
  var connected = false
    private set
  val usbPermissionPromptOnScreen get() = _usbPermissionPromptCountFlow.value > 0

  override fun onServiceConnected(componentName: ComponentName?, iBinder: IBinder?) {
    val binder = iBinder as UsbDriverService.UsbDriverBinder
    binder.setListener(controllerHandler)
    binder.setStateListener(object : UsbDriverService.UsbDriverStateListener {
      override fun onUsbPermissionPromptStarting() {
        // Disable PiP auto-enter while the USB permission prompt is on-screen. This prevents
        // us from entering PiP while the user is interacting with the OS permission dialog.
        _usbPermissionPromptCountFlow.value++
      }

      override fun onUsbPermissionPromptCompleted() {
        _usbPermissionPromptCountFlow.value--
      }
    })
    binder.start()
    connected = true
  }

  override fun onServiceDisconnected(componentName: ComponentName?) {
    connected = false
  }
}