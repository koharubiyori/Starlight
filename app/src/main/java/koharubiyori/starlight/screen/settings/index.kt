package koharubiyori.starlight.screen.settings

import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import koharubiyori.starlight.Globals
import koharubiyori.starlight.R
import koharubiyori.starlight.component.commonDialog.ButtonConfig
import koharubiyori.starlight.component.commonDialog.CommonAlertDialogProps
import koharubiyori.starlight.component.commonDialog.CommonRadioDialogProps
import koharubiyori.starlight.component.commonDialog.CommonRadioItem
import koharubiyori.starlight.component.styled.StyledTopAppBar
import koharubiyori.starlight.component.styled.TopAppBarTitle
import koharubiyori.starlight.nav.NavEntryKey
import koharubiyori.starlight.screen.settings.component.SettingsScreenItem
import koharubiyori.starlight.store.proto.AnalogStickForScrolling
import koharubiyori.starlight.store.proto.AudioConfiguration
import koharubiyori.starlight.store.proto.FormatOption
import koharubiyori.starlight.store.proto.FramePacing
import koharubiyori.starlight.store.proto.SettingsStoreData
import koharubiyori.starlight.util.updateLocale
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

object SettingsEntryKey : NavEntryKey

@Composable
fun SettingsScreen() {
  val model = hiltViewModel<SettingsScreenModel>()
  val activity = LocalActivity.current!! as ComponentActivity
  val coroutine = rememberCoroutineScope()
  val settings by model.settingsStore.settings.collectAsState(SettingsStoreData.getDefaultInstance())

  fun showVideoResolutionSelector() {
    val nativeSize = SettingsScreenUtils.getNativeResolution(activity, true)
    val nativeLabel = activity.getString(R.string._native) + " (${nativeSize.width}×${nativeSize.height})"
    val fullScreenSize = SettingsScreenUtils.getNativeResolution(activity, false)
    val fullScreenLabel = activity.getString(R.string.s_native_full_screen) + " (${fullScreenSize.width}×${fullScreenSize.height})"

    Globals.commonRadioDialog.show(CommonRadioDialogProps(
      items = listOf(
        CommonRadioItem("720P", VideoResolutions.P720),
        CommonRadioItem("1080P", VideoResolutions.P1080),
        CommonRadioItem("1440P", VideoResolutions.P1440),
        CommonRadioItem("4K", VideoResolutions.K4),
        CommonRadioItem(nativeLabel, VideoResolutions.NATIVE),
        CommonRadioItem(fullScreenLabel, VideoResolutions.NATIVE_FULL_SCREEN)
      ),
      defaultValue = VideoResolutions.match(activity, settings.width, settings.height) ?: VideoResolutions.P1080,
      onSelectedItemChange = {
        if (it == VideoResolutions.NATIVE || it == VideoResolutions.NATIVE_FULL_SCREEN) {
          Globals.commonAlertDialog.show(CommonAlertDialogProps(
            title = activity.getString(R.string.native_resolution_warning),
            content = { Text(stringResource(R.string.s_native_resolution_warning_content)) }
          ))
        }
      },
      onCheck = {
        coroutine.launch {
          val size = when (it) {
            VideoResolutions.NATIVE -> nativeSize
            VideoResolutions.NATIVE_FULL_SCREEN -> fullScreenSize
            else -> Size(it.width!!, it.height!!)
          }

          model.settingsStore.updateSettings {
            width = size.width
            height = size.height
          }
        }
      }
    ))
  }

  fun showVideoFrameRateSelector() {
    Globals.commonRadioDialog.show(CommonRadioDialogProps<Int>(
      items = listOf(30, 60, 90, 120).map { CommonRadioItem("$it FPS", it) }.toList(),
      defaultValue = settings!!.fps,
      onCheck = {
        coroutine.launch {
          model.settingsStore.updateSettings { fps = it }
        }
      }
    ))
  }

  fun showVideoBitrateSelector() {
    var value by mutableFloatStateOf(settings.bitrate.toFloat())
    val selectedResolution = VideoResolutions.match(activity, settings.width, settings.height)
      ?: VideoResolutions.NATIVE
    Globals.commonAlertDialog.show(CommonAlertDialogProps(
      title = activity.getString(R.string.video_bitrate),
      content = {
        Column(
          modifier = Modifier.fillMaxWidth(),
          horizontalAlignment = Alignment.CenterHorizontally
        ) {
          Slider(
            value = value,
            valueRange = 1f..150f,
            steps = 148,
            onValueChange = { value = it }
          )
          Box(
            modifier = Modifier
              .padding(top = 10.dp)
              .width(130.dp)
              .height(36.dp)
              .clip(CircleShape)
              .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
          ) {
            Text(
              text = "${value.roundToInt()} Mbps",
              color = MaterialTheme.colorScheme.onPrimary,
              fontSize = 20.sp,
              fontWeight = FontWeight.Bold
            )
          }
        }
      },
      onPrimaryButtonClick = {
        val bitrates = settings.bitrates.toBuilder()
        when (selectedResolution) {
          VideoResolutions.P720 -> bitrates.p720 = value.roundToInt()
          VideoResolutions.P1080 -> bitrates.p1080 = value.roundToInt()
          VideoResolutions.P1440 -> bitrates.p1440 = value.roundToInt()
          VideoResolutions.K4 -> bitrates.p4K = value.roundToInt()
          VideoResolutions.NATIVE,
          VideoResolutions.NATIVE_FULL_SCREEN -> bitrates.native = value.roundToInt()
        }
        coroutine.launch {
          model.settingsStore.updateSettings { this.bitrates = bitrates.build() }
        }
      }
    ))
  }

  fun showVideoFramePacingSelector() {
    Globals.commonRadioDialog.show(CommonRadioDialogProps(
      items = listOf(
        CommonRadioItem(
          value = FramePacing.MIN_LATENCY,
          label = activity.getString(R.string.s_pacing_latency)
        ),
        CommonRadioItem(
          value = FramePacing.BALANCED,
          label = activity.getString(R.string.s_pacing_balanced)
        ),
        CommonRadioItem(
          value = FramePacing.CAP_FPS,
          label = activity.getString(R.string.s_pacing_balanced_alt)
        ),
        CommonRadioItem(
          value = FramePacing.MAX_SMOOTHNESS,
          label = activity.getString(R.string.s_pacing_smoothness)
        ),
      ),
      defaultValue = settings!!.framePacing,
      onCheck = {
        coroutine.launch {
          model.settingsStore.updateSettings { framePacing = it }
        }
      }
    ))
  }

  fun showGamepadDeadzoneSlider() {
    var value by mutableFloatStateOf(settings!!.deadzonePercentage.toFloat())
    Globals.commonAlertDialog.show(CommonAlertDialogProps(
      title = activity.getString(R.string.s_adjust_deadzone),
      content = {
        Column(
          modifier = Modifier
            .fillMaxWidth(),
          horizontalAlignment = Alignment.CenterHorizontally
        ) {
          Slider(
            value = value,
            valueRange = 0f..20f,
            steps = 20,
            onValueChange = { value = it }
          )
          Box(
            modifier = Modifier
              .padding(top = 10.dp)
              .width(60.dp)
              .height(36.dp)
              .clip(CircleShape)
              .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
          ) {
            Text(
              modifier = Modifier,
              text = value.toInt().toString() + "%",
              color = MaterialTheme.colorScheme.onPrimary,
              fontSize = 20.sp,
              fontWeight = FontWeight.Bold
            )
          }
        }
      },
      onPrimaryButtonClick = {
        coroutine.launch {
          model.settingsStore.updateSettings { deadzonePercentage = value.toInt() }
        }
      }
    ))
  }

  fun showAnalogScrollingSelector() {
    Globals.commonRadioDialog.show(CommonRadioDialogProps(
      items = listOf(
        CommonRadioItem(
          activity.getString(R.string.s_analog_scroll_none),
          AnalogStickForScrolling.NONE
        ),
        CommonRadioItem(
          activity.getString(R.string.s_analog_scroll_left),
          AnalogStickForScrolling.LEFT
        ),
        CommonRadioItem(
          activity.getString(R.string.s_analog_scroll_right),
          AnalogStickForScrolling.RIGHT
        )
      ),
      defaultValue = settings!!.analogStickForScrolling,
      onCheck = {
        coroutine.launch {
          model.settingsStore.updateSettings { analogStickForScrolling = it }
        }
      }
    ))
  }

  fun showVibrateFallbackStrengthSlider() {
    var value by mutableFloatStateOf(settings!!.vibrateFallbackToDeviceStrength.toFloat())
    Globals.commonAlertDialog.show(CommonAlertDialogProps(
      title = activity.getString(R.string.s_vibrate_fallback_strength),
      content = {
        Column(
          modifier = Modifier
            .fillMaxWidth(),
          horizontalAlignment = Alignment.CenterHorizontally
        ) {
          Slider(
            value = value,
            valueRange = 1f..200f,
            onValueChange = { value = it.toInt().toFloat() }
          )
          Box(
            modifier = Modifier
              .padding(top = 10.dp)
              .width(80.dp)
              .height(36.dp)
              .clip(CircleShape)
              .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
          ) {
            Text(
              modifier = Modifier,
              text = value.toInt().toString() + "%",
              color = MaterialTheme.colorScheme.onPrimary,
              fontSize = 20.sp,
              fontWeight = FontWeight.Bold
            )
          }
        }
      },
      onPrimaryButtonClick = {
        coroutine.launch {
          model.settingsStore.updateSettings { vibrateFallbackToDeviceStrength = value.toInt() }
        }
      }
    ))
  }

  fun showAudioConfigurationSelector() {
    Globals.commonRadioDialog.show(CommonRadioDialogProps(
      items = listOf(
        CommonRadioItem(
          activity.getString(R.string.s_surround_sound_config_stereo),
          AudioConfiguration.STEREO
        ),
        CommonRadioItem(
          activity.getString(R.string.s_surround_sound_config_51surround),
          AudioConfiguration.SURROUND_51
        ),
        CommonRadioItem(
          activity.getString(R.string.s_surround_sound_config_71surround),
          AudioConfiguration.SURROUND_71
        )
      ),
      defaultValue = settings!!.audioConfiguration,
      onCheck = {
        coroutine.launch {
          model.settingsStore.updateSettings { audioConfiguration = it }
        }
      }
    ))
  }

  fun showOnScreenControllerOpacitySlider() {
    var value by mutableFloatStateOf(settings!!.oscOpacity.toFloat())
    Globals.commonAlertDialog.show(CommonAlertDialogProps(
      title = activity.getString(R.string.opacity),
      content = {
        Column(
          modifier = Modifier
            .fillMaxWidth(),
          horizontalAlignment = Alignment.CenterHorizontally
        ) {
          Slider(
            value = value,
            valueRange = 1f..100f,
            onValueChange = { value = it.toInt().toFloat() }
          )
          Box(
            modifier = Modifier
              .padding(top = 10.dp)
              .width(80.dp)
              .height(36.dp)
              .clip(CircleShape)
              .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
          ) {
            Text(
              modifier = Modifier,
              text = value.toInt().toString() + "%",
              color = MaterialTheme.colorScheme.onPrimary,
              fontSize = 20.sp,
              fontWeight = FontWeight.Bold
            )
          }
        }
      },
      onPrimaryButtonClick = {
        coroutine.launch {
          model.settingsStore.updateSettings { oscOpacity = value.toInt() }
        }
      }
    ))
  }

  fun showResetOnScreenControllerLayoutDialog() {
    Globals.commonAlertDialog.show(CommonAlertDialogProps(
      content = { Text(stringResource(R.string.s_reset_osc_dialog)) },
      onPrimaryButtonClick = {
        // TODO: the logic of reset on screen controller layout
      },
      secondaryButton = ButtonConfig.cancelButton(activity.getString(R.string.cancel))
    ))
  }

  fun showLanguageListSelector() {
    Globals.commonRadioDialog.show(CommonRadioDialogProps(
      items = SettingsScreenUtils.languageList(activity),
      defaultValue = settings!!.language,
      onCheck = {
        coroutine.launch {
          model.settingsStore.updateSettings { language = it }
          activity.updateLocale(if (it == "auto") Locale.getDefault() else Locale.forLanguageTag(it))
        }
      }
    ))
  }

  fun showEncoderSelector() {
    Globals.commonRadioDialog.show(CommonRadioDialogProps(
      items = listOf(
        CommonRadioItem(
          label = activity.getString(R.string.s_video_format_auto),
          value = FormatOption.AUTO
        ),
        CommonRadioItem(
          label = activity.getString(R.string.s_video_format_av1_always),
          value = FormatOption.AV1
        ),
        CommonRadioItem(
          label = activity.getString(R.string.s_video_format_hevc_always),
          value = FormatOption.HEVC
        ),
        CommonRadioItem(
          label = activity.getString(R.string.s_video_format_h264_always),
          value = FormatOption.H264
        )
      ),
      defaultValue = settings!!.videoFormat,
      onCheck = {
        coroutine.launch { model.settingsStore.updateSettings { videoFormat = it } }
      }
    ))
  }

  Scaffold(
    modifier = Modifier
      .imePadding(),
    topBar = {
      StyledTopAppBar(
        title = { TopAppBarTitle(text = stringResource(R.string.settings)) },
      )
    }
  ) {
    Column(
      modifier = Modifier
        .padding(it)
        .fillMaxSize()
        .verticalScroll(rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }),
    ) {
      GroupTitle(stringResource(R.string.basic_settings))
      SettingsScreenItem(
        title = stringResource(R.string.video_resolution),
        subtext = stringResource(R.string.s_video_resolution_desc),
        onClick = { coroutine.launch { showVideoResolutionSelector() } },
      ) {
        Text(
          color = MaterialTheme.colorScheme.primary,
          text = when (VideoResolutions.match(activity, settings.width, settings.height) ?: VideoResolutions.P1080) {
            VideoResolutions.P720 -> "720P"
            VideoResolutions.P1080 -> "1080P"
            VideoResolutions.P1440 -> "1440P"
            VideoResolutions.K4 -> "4K"
            VideoResolutions.NATIVE -> stringResource(R.string._native)
            VideoResolutions.NATIVE_FULL_SCREEN -> stringResource(R.string.s_native_full_screen)
          }
        )
      }
      SettingsScreenItem(
        title = stringResource(R.string.video_frame_rate),
        subtext = stringResource(R.string.s_video_frame_rate_desc),
        onClick = { showVideoFrameRateSelector() }
      ) {
        Text(
          color = MaterialTheme.colorScheme.primary,
          text = settings.fps.toString() + " FPS"
        )
      }
      SettingsScreenItem(
        title = stringResource(R.string.s_custom_bitrate),
        subtext = stringResource(R.string.s_auto_bitrate_desc),
        onClick = {
          coroutine.launch {
            model.settingsStore.updateSettings { customBitrate = !settings.customBitrate }
          }
        }
      ) {
        Switch(
          checked = settings.customBitrate,
          onCheckedChange = { enabled ->
            coroutine.launch {
              model.settingsStore.updateSettings { customBitrate = enabled }
            }
          }
        )
      }
      SettingsScreenItem(
        title = stringResource(R.string.video_bitrate),
        subtext = stringResource(R.string.s_video_bitrate_desc),
        enabled = settings.customBitrate,
        onClick = { showVideoBitrateSelector() }
      ) {
        Text(
          color = MaterialTheme.colorScheme.primary,
          text = "${settings.bitrate} Mbps"
        )
      }
      SettingsScreenItem(
        title = stringResource(R.string.video_frame_pacing),
        subtext = stringResource(R.string.s_video_frame_pacing_desc),
        onClick = { showVideoFramePacingSelector() }
      ) {
        Text(
          color = MaterialTheme.colorScheme.primary,
          text = when(settings.framePacing) {
            FramePacing.MIN_LATENCY -> stringResource(R.string.s_pacing_latency)
            FramePacing.BALANCED -> stringResource(R.string.s_pacing_balanced)
            FramePacing.CAP_FPS -> stringResource(R.string.s_pacing_balanced_alt)
            FramePacing.MAX_SMOOTHNESS -> stringResource(R.string.s_pacing_smoothness)
            else -> error("Unknown frame pacing: ${settings.framePacing}")
          }
        )
      }
      SettingsItemWithSwitch(
        title = stringResource(R.string.s_stretch_video_full_screen),
        checked = settings.stretchVideo,
        onClick = {
          coroutine.launch { model.settingsStore.updateSettings { stretchVideo = it } }
        }
      )

      GroupTitle(stringResource(R.string.audio_settings))
      SettingsScreenItem(
        title = stringResource(R.string.s_surround_sound_config),
        subtext = stringResource(R.string.s_surround_sound_config_desc),
        onClick = { showAudioConfigurationSelector() }
      ) {
        Text(
          color = MaterialTheme.colorScheme.primary,
          text = when(settings.audioConfiguration) {
            AudioConfiguration.SURROUND_51 -> "5.1"
            AudioConfiguration.SURROUND_71 -> "7.1"
            else -> stringResource(R.string.s_surround_sound_config_stereo)
          }
        )
      }
      SettingsItemWithSwitch(
        title = stringResource(R.string.s_enable_system_equalizer),
        subtext = stringResource(R.string.s_enable_system_equalizer_desc),
        checked = settings.enableAudioFx,
        onClick = {
          coroutine.launch { model.settingsStore.updateSettings { enableAudioFx = it } }
        }
      )

      GroupTitle(stringResource(R.string.gamepad_settings))
      SettingsScreenItem(
        title = stringResource(R.string.s_adjust_deadzone),
        subtext = stringResource(R.string.s_adjust_deadzone_desc),
        onClick = { showGamepadDeadzoneSlider() }
      ) {
        Text(
          color = MaterialTheme.colorScheme.primary,
          text = settings.deadzonePercentage.toString() + "%"
        )
      }
      SettingsItemWithSwitch(
        title = stringResource(R.string.s_multi_controller),
        subtext = stringResource(R.string.s_multi_controller_desc),
        checked = settings.multiController,
        onClick = {
          coroutine.launch { model.settingsStore.updateSettings { multiController = it } }
        }
      )
      SettingsItemWithSwitch(
        title = stringResource(R.string.s_xbox_driver),
        subtext = stringResource(R.string.s_xbox_driver_desc),
        checked = settings.usbDriver,
        onClick = {
          coroutine.launch { model.settingsStore.updateSettings { usbDriver = it } }
        }
      )
      SettingsItemWithSwitch(
        title = stringResource(R.string.s_usb_bind_all),
        subtext = stringResource(R.string.s_usb_bind_all_desc),
        checked = settings.bindAllUsb,
        onClick = {
          coroutine.launch { model.settingsStore.updateSettings { bindAllUsb = it } }
        }
      )
      SettingsItemWithSwitch(
        title = stringResource(R.string.s_mouse_emulation),
        subtext = stringResource(R.string.s_mouse_emulation_desc),
        checked = settings.mouseEmulation,
        onClick = {
          coroutine.launch { model.settingsStore.updateSettings { mouseEmulation = it } }
        }
      )
      SettingsScreenItem(
        title = stringResource(R.string.s_analog_scrolling),
        subtext = stringResource(R.string.s_analog_scrolling_desc),
        onClick = { showAnalogScrollingSelector() }
      )
      SettingsItemWithSwitch(
        title = stringResource(R.string.s_vibrate_fallback),
        subtext = stringResource(R.string.s_vibrate_fallback_desc),
        checked = settings.vibrateFallbackToDevice,
        onClick = {
          coroutine.launch { model.settingsStore.updateSettings { vibrateFallbackToDevice = it } }
        }
      )
      SettingsScreenItem(
        title = stringResource(R.string.s_vibrate_fallback_strength),
        subtext = stringResource(R.string.s_vibrate_fallback_strength_desc),
        onClick = { showVibrateFallbackStrengthSlider() }
      ) {
        Text(
          color = MaterialTheme.colorScheme.primary,
          text = settings.vibrateFallbackToDeviceStrength.toInt().toString() + "%"
        )
      }
      SettingsItemWithSwitch(
        title = stringResource(R.string.s_flip_face_buttons),
        subtext = stringResource(R.string.s_flip_face_buttons_desc),
        checked = settings.flipFaceButtons,
        onClick = {
          coroutine.launch { model.settingsStore.updateSettings { flipFaceButtons = it } }
        }
      )
      SettingsItemWithSwitch(
        title = stringResource(R.string.s_touchpad_as_mouse),
        subtext = stringResource(R.string.s_flip_face_buttons_desc),
        checked = settings.gamepadTouchpadAsMouse,
        onClick = {
          coroutine.launch { model.settingsStore.updateSettings { gamepadTouchpadAsMouse = it } }
        }
      )
      SettingsItemWithSwitch(
        title = stringResource(R.string.s_motion_sensors),
        subtext = stringResource(R.string.s_motion_sensors_desc),
        checked = settings.gamepadMotionSensors,
        onClick = {
          coroutine.launch { model.settingsStore.updateSettings { gamepadMotionSensors = it } }
        }
      )
      SettingsItemWithSwitch(
        title = stringResource(R.string.s_motion_fallback),
        subtext = stringResource(R.string.s_motion_fallback_desc),
        checked = settings.gamepadMotionSensorsFallbackToDevice,
        onClick = {
          coroutine.launch { model.settingsStore.updateSettings { gamepadMotionSensorsFallbackToDevice = it } }
        }
      )

      GroupTitle(stringResource(R.string.input_settings))
      SettingsItemWithSwitch(
        title = stringResource(R.string.s_touchscreen_trackpad),
        subtext = stringResource(R.string.s_touchscreen_trackpad_desc),
        checked = settings.touchscreenTrackpad,
        onClick = {
          coroutine.launch { model.settingsStore.updateSettings { touchscreenTrackpad = it } }
        }
      )
      SettingsItemWithSwitch(
        title = stringResource(R.string.s_mouse_nav_buttons),
        subtext = stringResource(R.string.s_mouse_nav_buttons_desc),
        checked = settings.mouseNavButtons,
        onClick = {
          coroutine.launch { model.settingsStore.updateSettings { mouseNavButtons = it } }
        }
      )
      SettingsItemWithSwitch(
        title = stringResource(R.string.s_absolute_mouse_mode),
        subtext = stringResource(R.string.s_absolute_mouse_mode_desc),
        checked = settings.absoluteMouseMode,
        onClick = {
          coroutine.launch { model.settingsStore.updateSettings { absoluteMouseMode = it } }
        }
      )

      GroupTitle(stringResource(R.string.s_on_screen_controls_settings))
      SettingsItemWithSwitch(
        title = stringResource(R.string.s_show_onscreen_controls),
        subtext = stringResource(R.string.s_show_onscreen_controls_desc),
        checked = settings.onscreenController,
        onClick = {
          coroutine.launch { model.settingsStore.updateSettings { onscreenController = it } }
        }
      )
      SettingsItemWithSwitch(
        title = stringResource(R.string.s_vibrate_osc),
        subtext = stringResource(R.string.s_vibrate_osc_desc),
        checked = settings.vibrateOsc,
        enabled = settings.onscreenController,
        onClick = {
          coroutine.launch { model.settingsStore.updateSettings { vibrateOsc = it } }
        }
      )
      SettingsItemWithSwitch(
        title = stringResource(R.string.s_only_l3r3),
        subtext = stringResource(R.string.s_only_l3r3_desc),
        checked = settings.onlyL3R3,
        enabled = settings.onscreenController,
        onClick = {
          coroutine.launch { model.settingsStore.updateSettings { onlyL3R3 = it } }
        }
      )
      SettingsItemWithSwitch(
        title = stringResource(R.string.s_show_guide_button),
        subtext = stringResource(R.string.s_show_guide_button_desc),
        checked = settings.showGuideButton,
        enabled = settings.onscreenController,
        onClick = {
          coroutine.launch { model.settingsStore.updateSettings { showGuideButton = it } }
        }
      )
      SettingsScreenItem(
        title = stringResource(R.string.s_osc_opacity),
        subtext = stringResource(R.string.s_osc_opacity_desc),
        enabled = settings.onscreenController,
        onClick = { showOnScreenControllerOpacitySlider() }
      ) {
        Text(
          color = MaterialTheme.colorScheme.primary,
          text = settings.oscOpacity.toInt().toString() + "%"
        )
      }
      SettingsScreenItem(
        title = stringResource(R.string.s_reset_osc),
        subtext = stringResource(R.string.s_reset_osc_desc),
        onClick = { showResetOnScreenControllerLayoutDialog() }
      )

      GroupTitle(stringResource(R.string.host_settings))
      SettingsItemWithSwitch(
        title = stringResource(R.string.s_host_audio),
        subtext = stringResource(R.string.s_host_audio_desc),
        checked = settings.playHostAudio,
        onClick = {
          coroutine.launch { model.settingsStore.updateSettings { playHostAudio = it } }
        }
      )

      GroupTitle(stringResource(R.string.ui_settings))
      SettingsItemWithSwitch(
        title = stringResource(R.string.s_enable_pip),
        subtext = stringResource(R.string.s_enable_pip_desc),
        checked = settings.enablePip,
        onClick = {
          coroutine.launch { model.settingsStore.updateSettings { enablePip = it } }
        }
      )
      SettingsScreenItem(
        title = stringResource(R.string.s_language_list),
        subtext = stringResource(R.string.s_language_list_desc),
        onClick = { showLanguageListSelector() }
      )

      GroupTitle(stringResource(R.string.advanced_settings))
      SettingsItemWithSwitch(
        title = stringResource(R.string.s_unlock_fps),
        subtext = stringResource(R.string.s_unlock_fps_desc),
        checked = settings.unlockFps,
        onClick = {
          coroutine.launch { model.settingsStore.updateSettings { unlockFps = it } }
        }
      )
      SettingsItemWithSwitch(
        title = stringResource(R.string.s_reduce_refresh_rate),
        subtext = stringResource(R.string.s_reduce_refresh_rate_desc),
        checked = settings.reduceRefreshRate,
        onClick = {
          coroutine.launch { model.settingsStore.updateSettings { reduceRefreshRate = it } }
        }
      )
      SettingsItemWithSwitch(
        title = stringResource(R.string.s_disable_warnings),
        subtext = stringResource(R.string.s_disable_warnings_desc),
        checked = settings.disableWarnings,
        onClick = {
          coroutine.launch { model.settingsStore.updateSettings { disableWarnings = it } }
        }
      )
      SettingsScreenItem(
        title = stringResource(R.string.s_video_format),
        subtext = stringResource(R.string.s_video_format_desc),
        onClick = { showEncoderSelector() }
      ) {
        Text(
          color = MaterialTheme.colorScheme.primary,
          text = when(settings.videoFormat) {
            FormatOption.AUTO -> stringResource(R.string.auto)
            FormatOption.AV1 -> "AV1"
            FormatOption.HEVC -> "HEVC"
            FormatOption.H264 -> "H264"
            else -> error("Unknown video format: ${settings.videoFormat}")
          }
        )
      }
      SettingsItemWithSwitch(
        title = stringResource(R.string.s_enable_hdr),
        subtext = stringResource(R.string.s_enable_hdr_desc),
        checked = settings.enableHdr,
        onClick = {
          coroutine.launch { model.settingsStore.updateSettings { enableHdr = it } }
        }
      )
      SettingsItemWithSwitch(
        title = stringResource(R.string.s_full_range),
        subtext = stringResource(R.string.s_full_range_desc),
        checked = settings.fullRange,
        onClick = {
          coroutine.launch { model.settingsStore.updateSettings { fullRange = it } }
        }
      )
      SettingsItemWithSwitch(
        title = stringResource(R.string.s_enable_perf_overlay),
        subtext = stringResource(R.string.s_enable_perf_overlay_desc),
        checked = settings.enablePerfOverlay,
        onClick = {
          coroutine.launch { model.settingsStore.updateSettings { enablePerfOverlay = it } }
        }
      )
      SettingsItemWithSwitch(
        title = stringResource(R.string.s_enable_post_stream_toast),
        subtext = stringResource(R.string.s_enable_post_stream_toast_desc),
        checked = settings.enableLatencyToast,
        onClick = {
          coroutine.launch { model.settingsStore.updateSettings { enableLatencyToast = it } }
        }
      )
    }
  }
}

@Composable
private fun GroupTitle(text: String) {
  Text(
    modifier = Modifier
      .padding(top = 10.dp, start = 20.dp, bottom = 5.dp),
    text = text,
    fontSize = 16.sp,
    color = MaterialTheme.colorScheme.primary,
    fontWeight = FontWeight.Bold
  )
}

@Composable
private fun SettingsItemWithSwitch(
  title: String,
  checked: Boolean,
  subtext: String? = null,
  enabled: Boolean = true,
  onClick: (Boolean) -> Unit
) {
  SettingsScreenItem(
    title = title,
    subtext = subtext,
    enabled = enabled,
    onClick = { onClick(!checked) }
  ) {
    Switch(
      checked = checked,
      enabled = enabled,
      onCheckedChange = onClick
    )
  }
}

enum class VideoResolutions(
  val width: Int? = null,
  val height: Int? = null,
  val bitrateAt60Fps: Int? = null
) {
  P720(1280, 720, 10),
  P1080(1920, 1080, 20),
  P1440(2560, 1440, 40),
  K4(3840, 2160, 80),
  NATIVE,
  NATIVE_FULL_SCREEN;

  companion object {
    fun match(activity: ComponentActivity, width: Int, height: Int): VideoResolutions? {
      val matchedItem = VideoResolutions.entries.firstOrNull { it.width == width && it.height == height }
      if (matchedItem != null) return matchedItem

      val nativeSize = SettingsScreenUtils.getNativeResolution(activity, true)
      val fullScreenSize = SettingsScreenUtils.getNativeResolution(activity, false)

      if (nativeSize.width == width && nativeSize.height == height) return NATIVE
      if (fullScreenSize.width == width && fullScreenSize.height == height) return NATIVE_FULL_SCREEN
      return null
    }
  }
}

enum class SupportedFps(val fps: Int, val bitrateFactor: Float) {
  FPS_30(30, 0.5f),
  FPS_60(60, 1f),
  FPS_90(90, 1.2f),
  FPS_120(120, 1.4f)
}