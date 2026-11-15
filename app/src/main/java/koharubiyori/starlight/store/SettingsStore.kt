package koharubiyori.starlight.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.dataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import jakarta.inject.Inject
import jakarta.inject.Singleton
import koharubiyori.starlight.Globals
import koharubiyori.starlight.store.proto.AnalogStickForScrolling
import koharubiyori.starlight.store.proto.AudioConfiguration
import koharubiyori.starlight.store.proto.FormatOption
import koharubiyori.starlight.store.proto.SettingsStoreData
import koharubiyori.starlight.store.proto.SettingsStoreDataKt
import koharubiyori.starlight.store.proto.copy
import koharubiyori.starlight.store.serializer.SettingsStoreDataSerializer
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<SettingsStoreData> by dataStore(
  fileName = "settings.pb",
  serializer = SettingsStoreDataSerializer
)

@Singleton
class SettingsStore @Inject constructor(
  @param:ApplicationContext private val context: Context
) {
  private val dataStore get() = context.dataStore

  val storedSettings get() = dataStore.data

  val settings get() = dataStore.data.map {
    it.copy {
      if (!it.hasWidth()) width = 1920
      if (!it.hasHeight()) height = 1080
      if (!it.hasFps()) fps = 60
      if (!it.hasCustomBitrate()) customBitrate = false
      if (!it.hasStretchVideo()) stretchVideo = false

      if (!it.hasAudioConfiguration()) audioConfiguration = AudioConfiguration.STEREO
      if (!it.hasEnableAudioFx()) enableAudioFx = false

      if (!it.hasDeadzonePercentage()) deadzonePercentage = 7
      if (!it.hasMultiController()) multiController = true
      if (!it.hasUsbDriver()) usbDriver = true
      if (!it.hasBindAllUsb()) bindAllUsb = false
      if (!it.hasAnalogStickForScrolling()) analogStickForScrolling = AnalogStickForScrolling.RIGHT
      if (!it.hasVibrateFallbackToDevice()) vibrateFallbackToDevice = false
      if (!it.hasVibrateFallbackToDeviceStrength()) vibrateFallbackToDeviceStrength = 100
      if (!it.hasFlipFaceButtons()) flipFaceButtons = false
      if (!it.hasGamepadTouchpadAsMouse()) gamepadTouchpadAsMouse = false
      if (!it.hasGamepadMotionSensors()) gamepadMotionSensors = false
      if (!it.hasGamepadMotionSensorsFallbackToDevice()) gamepadMotionSensorsFallbackToDevice = false

      if (!it.hasTouchscreenTrackpad()) touchscreenTrackpad = true
      if (!it.hasMouseNavButtons()) mouseNavButtons = false
      if (!it.hasAbsoluteMouseMode()) absoluteMouseMode = false

      if (!it.hasOnscreenController()) onscreenController = false
      if (!it.hasVibrateOsc()) vibrateOsc = true
      if (!it.hasOnlyL3R3()) onlyL3R3 = false
      if (!it.hasShowGuideButton()) showGuideButton = true
      if (!it.hasOscOpacity()) oscOpacity = 50

      if (!it.hasPlayHostAudio()) playHostAudio = false

      if (!it.hasEnablePip()) enablePip = false
      if (!it.hasLanguage()) language = "auto"

      if (!it.hasUnlockFps()) unlockFps = false
      if (!it.hasReduceRefreshRate()) reduceRefreshRate = false
      if (!it.hasDisableWarnings()) disableWarnings = false
      if (!it.hasVideoFormat()) videoFormat = FormatOption.AUTO
      if (!it.hasEnableHdr()) enableHdr = false
      if (!it.hasFullRange()) fullRange = false
      if (!it.hasEnablePerfOverlay()) enablePerfOverlay = false
      if (!it.hasEnableLatencyToast()) enableLatencyToast = false
    }
  }

  suspend fun updateSettings(block: SettingsStoreDataKt.Dsl.() -> Unit) {
    dataStore.updateData { it.copy(block) }
  }
}