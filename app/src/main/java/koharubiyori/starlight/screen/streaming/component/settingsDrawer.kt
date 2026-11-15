package koharubiyori.starlight.screen.streaming.component

import android.util.Size
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.node.Ref
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import koharubiyori.starlight.Globals
import koharubiyori.starlight.R
import koharubiyori.starlight.component.commonDialog.ButtonConfig
import koharubiyori.starlight.component.commonDialog.CommonAlertDialogProps
import koharubiyori.starlight.nav.LocalBackStack
import koharubiyori.starlight.nvstream.input.KeyboardPacket
import koharubiyori.starlight.screen.settings.SettingsEntryKey
import koharubiyori.starlight.screen.settings.SettingsScreenUtils
import koharubiyori.starlight.screen.settings.VideoResolutions
import koharubiyori.starlight.screen.streaming.StreamingScreenModel
import koharubiyori.starlight.screen.streaming.streamConnection.InputCaptureListener
import koharubiyori.starlight.util.debugPrint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

@Composable
fun SettingsDrawer(
  sessionSettings: SessionSettings,
  inputCaptureListener: InputCaptureListener,
  onSettingsChanged: () -> Unit,
  onSessionSettingsChanged: (SessionSettings) -> Unit,
  ref: Ref<EdgeModalDrawerRef> = Ref(),
) {
  val activity = LocalActivity.current!! as ComponentActivity
  val model = hiltViewModel<StreamingScreenModel>()
  val backStack = LocalBackStack.current
  val coroutine = rememberCoroutineScope()
  val settingsOrNull by model.settingsStore.settings.collectAsState(null)
  val reminderOrNull by model.reminderStore.reminder.collectAsState(null)
  if (settingsOrNull == null) return

  val settings = settingsOrNull!!
  var selectedResolution by remember(settings.width, settings.height) {
    mutableStateOf(
      VideoResolutions.match(activity, settings.width, settings.height)
        ?: VideoResolutions.P1080
    )
  }
  var selectedFps by remember(settings.fps) { mutableIntStateOf(settings.fps) }
  var selectedTouchInputType by remember(settings.touchscreenTrackpad, settings.onscreenController) {
    mutableStateOf(when {
      settings.onscreenController -> TouchInputType.GAME_CONTROLLER
      settings.touchscreenTrackpad -> TouchInputType.TRACKPAD
      else -> TouchInputType.TOUCH
    })
  }
  var localSessionSettings by remember { mutableStateOf(sessionSettings) }

  val nativeSize = remember { SettingsScreenUtils.getNativeResolution(activity, true) }
  val fullScreenSize = remember { SettingsScreenUtils.getNativeResolution(activity, false) }
  val settingsChanged = remember(settings, selectedResolution, selectedFps, selectedTouchInputType) {
    val currentResolution = VideoResolutions.match(activity, settings.width, settings.height)
    if (currentResolution != selectedResolution) return@remember true
    if (settings.fps != selectedFps) return@remember true
    if (settings.onscreenController != (selectedTouchInputType == TouchInputType.GAME_CONTROLLER)) return@remember true
    if (settings.touchscreenTrackpad != (selectedTouchInputType == TouchInputType.TRACKPAD)) return@remember true
    false
  }

  suspend fun applySettings() {
    if (settingsChanged) {
      val size = when (selectedResolution) {
        VideoResolutions.NATIVE -> nativeSize
        VideoResolutions.NATIVE_FULL_SCREEN -> fullScreenSize
        else -> Size(selectedResolution.width!!, selectedResolution.height!!)
      }

      model.settingsStore.updateSettings {
        width = size.width
        height = size.height
        fps = selectedFps
        when (selectedTouchInputType) {
          TouchInputType.TOUCH -> {
            onscreenController = false
            touchscreenTrackpad = false
          }
          TouchInputType.TRACKPAD -> {
            onscreenController = false
            touchscreenTrackpad = true
          }
          TouchInputType.GAME_CONTROLLER -> {
            onscreenController = true
            touchscreenTrackpad = false
          }
        }
      }
      onSettingsChanged()
    }

    onSessionSettingsChanged(localSessionSettings)
  }

  fun showShutdownDialog() {
    Globals.commonAlertDialog.show(CommonAlertDialogProps(
      content = {
        Text(stringResource(R.string.s_shutdown_confirmation))
      },
      secondaryButton = ButtonConfig.cancelButton(activity.getString(R.string.cancel)),
      onPrimaryButtonClick = {
        coroutine.launch {
          ref.value?.close()
          inputCaptureListener.sendPairedKeyInput(KeyEvent.KEYCODE_D, KeyboardPacket.MODIFIER_META)
          delay(1.seconds)
          inputCaptureListener.sendPairedKeyInput(KeyEvent.KEYCODE_F4, KeyboardPacket.MODIFIER_ALT)
          delay(1.seconds)
          inputCaptureListener.sendPairedKeyInput(KeyEvent.KEYCODE_ENTER)
        }
      },
    ))
  }

  EdgeModalDrawer(
    drawerWidth = 450.dp,
    ref = ref,
    showSwipeHint = reminderOrNull?.edgeDrawerSwipeHintShown == false,
    onSwipeHintFinished = {
      coroutine.launch {
        model.reminderStore.updateReminder { edgeDrawerSwipeHintShown = true }
      }
    }
  ) {
    Column(
      modifier = Modifier.fillMaxSize()
        .verticalScroll(rememberScrollState()),
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .height(40.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        IconButton(
          onClick = { backStack.add(SettingsEntryKey) }
        ) {
          Icon(
            imageVector = Icons.Rounded.Settings,
            contentDescription = null,
            tint = Color.White
          )
        }
        Text(
          text = stringResource(R.string.quickly_settings),
          style = MaterialTheme.typography.labelLarge,
          color = Color(0xFFCCCCCC),
        )
        IconButton(
          onClick = {
            coroutine.launch {
              applySettings()
              ref.value?.close()
            }
          }
        ) {
          Icon(
            imageVector = Icons.Rounded.Done,
            contentDescription = null,
            tint = Color.White
          )
        }
      }

      Column(
        modifier = Modifier
          .fillMaxSize()
          .padding(horizontal = 10.dp)
      ) {
        HorizontalSelect(
          modifier = Modifier
            .fillMaxWidth(),
          options = listOf(
            HorizontalSelectOption(VideoResolutions.P720, "720P"),
            HorizontalSelectOption(VideoResolutions.P1080, "1080P"),
            HorizontalSelectOption(VideoResolutions.P1440, "1440P"),
            HorizontalSelectOption(VideoResolutions.K4, "4K"),
            HorizontalSelectOption(VideoResolutions.NATIVE, stringResource(R.string._native)),
            HorizontalSelectOption(VideoResolutions.NATIVE_FULL_SCREEN) {
              Text(
                text = stringResource(R.string.s_native_full_screen),
                fontSize = MaterialTheme.typography.labelSmall.fontSize
              )
            }
          ),
          selectedOption = selectedResolution,
          onOptionSelected = { selectedResolution = it },
        )

        HorizontalSelect(
          modifier = Modifier
            .padding(top = 10.dp)
            .fillMaxWidth(),
          options = listOf(30, 60, 90, 120).map {
            HorizontalSelectOption(it, stringResource(R.string.f_frame_rate, it))
          },
          selectedOption = selectedFps,
          onOptionSelected = { selectedFps = it },
        )

        HorizontalSelect(
          modifier = Modifier
            .padding(top = 10.dp)
            .fillMaxWidth(),
          options = listOf(
            HorizontalSelectOption(TouchInputType.TOUCH) {
              Column(
                horizontalAlignment = Alignment.CenterHorizontally,
              ) {
                Icon(
                  modifier = Modifier
                    .size(22.dp),
                  imageVector = Icons.Rounded.TouchApp,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.onPrimary,
                )
                Text(stringResource(R.string.touch))
              }
            },
            HorizontalSelectOption(TouchInputType.TRACKPAD) {
              Column(
                horizontalAlignment = Alignment.CenterHorizontally,
              ) {
                Icon(
                  modifier = Modifier
                    .padding(3.dp)
                    .size(16.dp),
                  painter = painterResource(R.drawable.cursor),
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.onPrimary,
                )
                Text(stringResource(R.string.trackpad))
              }
            },
            HorizontalSelectOption(TouchInputType.GAME_CONTROLLER) {
              Column(
                horizontalAlignment = Alignment.CenterHorizontally,
              ) {
                Icon(
                  modifier = Modifier
                    .size(22.dp),
                  imageVector = Icons.Rounded.SportsEsports,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.onPrimary,
                )
                Text(stringResource(R.string.virtual_controller))
              }
            },
          ),
          selectedOption = selectedTouchInputType,
          onOptionSelected = { selectedTouchInputType = it },
        )

        FlowRow(
          modifier = Modifier
            .padding(top = 10.dp)
            .fillMaxWidth(),
          maxItemsInEachRow = 3,
          horizontalArrangement = Arrangement.spacedBy(10.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          FunctionButton(
            modifier = Modifier
              .weight(1f),
            text = stringResource(R.string.show_desktop),
            onClick = {
              inputCaptureListener.sendPairedKeyInput(KeyEvent.KEYCODE_D, KeyboardPacket.MODIFIER_META)
            }
          ) {
            Icon(
              imageVector = Icons.Rounded.DesktopWindows,
              contentDescription = null
            )
          }
          FunctionButton(
            modifier = Modifier
              .weight(1f),
            text = stringResource(R.string.pause_streaming),
            onClick = { backStack.removeLastOrNull() }
          ) {
            Icon(
              imageVector = Icons.Rounded.Pause,
              contentDescription = null
            )
          }
          FunctionButton(
            modifier = Modifier
              .weight(1f),
            text = stringResource(R.string.shutdown),
            onClick = { showShutdownDialog() }
          ) {
            Icon(
              imageVector = Icons.Rounded.PowerSettingsNew,
              contentDescription = null
            )
          }
          FunctionButton(
            modifier = Modifier
              .weight(1f),
            text = stringResource(R.string.keyboard_button),
            selected = localSessionSettings.imeButtonShown,
            onClick = { localSessionSettings = localSessionSettings.copy(imeButtonShown = it) }
          ) {
            Icon(
              imageVector = Icons.Rounded.Keyboard,
              contentDescription = null
            )
          }
          FunctionButton(
            modifier = Modifier
              .weight(1f),
            text = stringResource(R.string.function_keys),
            selected = localSessionSettings.functionKeysShown,
            onClick = { localSessionSettings = localSessionSettings.copy(functionKeysShown = it) }
          ) {
            Icon(
              imageVector = Icons.Rounded.Keyboard,
              contentDescription = null
            )
          }
          Spacer(Modifier.weight(1f))
        }
      }
    }
  }
}

private enum class TouchInputType {
  TOUCH, TRACKPAD, GAME_CONTROLLER
}

@Composable
private fun FunctionButton(
  modifier: Modifier = Modifier,
  text: String,
  selected: Boolean = false,
  onClick: (selected: Boolean) -> Unit,
  icon: @Composable () -> Unit
) {
  OutlinedButton(
    modifier = modifier
      .height(60.dp),
    shape = MaterialTheme.shapes.small,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
    contentPadding = PaddingValues.Zero,
    colors = ButtonDefaults.outlinedButtonColors().copy(
      containerColor = if (selected) MaterialTheme.colorScheme.primary else Color(0xFF1E1E1E)
    ),
    onClick = { onClick(!selected) }
  ) {
    CompositionLocalProvider(
      LocalContentColor provides Color.White
    ) {
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        icon()
        Text(
          text = text,
          fontSize = 12.sp
        )
      }
    }
  }
}

data class SessionSettings(
  val imeButtonShown: Boolean = false,
  val functionKeysShown: Boolean = false
)