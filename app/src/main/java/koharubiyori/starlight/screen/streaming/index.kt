package koharubiyori.starlight.screen.streaming

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.wifi.WifiManager
import android.view.InputDevice
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateValueAsState
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.MenuOpen
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.node.Ref
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.viewModelScope
import koharubiyori.starlight.Globals
import koharubiyori.starlight.R
import koharubiyori.starlight.compable.OneTimeLaunchedEffect
import koharubiyori.starlight.compable.autoFocus
import koharubiyori.starlight.compable.remember.rememberDisplayCutoutInsets
import koharubiyori.starlight.compable.remember.rememberScreenCornerRadii
import koharubiyori.starlight.component.commonDialog.CommonAlertDialogProps
import koharubiyori.starlight.nav.LocalBackStack
import koharubiyori.starlight.screen.settings.VideoResolutions
import koharubiyori.starlight.screen.streaming.component.EdgeModalDrawerRef
import koharubiyori.starlight.screen.streaming.component.PerformanceOverlay
import koharubiyori.starlight.screen.streaming.component.SettingsDrawer
import koharubiyori.starlight.screen.streaming.component.VirtualFunctionKeys
import koharubiyori.starlight.screen.streaming.streamConnection.StreamConnection
import koharubiyori.starlight.screen.streaming.util.MouseInputByTouchesHandler
import koharubiyori.starlight.screen.streaming.util.StreamView
import koharubiyori.starlight.screen.streaming.util.input.virtual_controller.VirtualController
import koharubiyori.starlight.screen.streaming.util.keyboardInput
import koharubiyori.starlight.screen.streaming.util.mouseInputByTouches
import koharubiyori.starlight.store.proto.ReminderStoreData
import koharubiyori.starlight.store.proto.SettingsStoreData
import koharubiyori.starlight.util.InitRef
import koharubiyori.starlight.util.NetworkUtil
import koharubiyori.starlight.util.toast
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.math.max
import kotlin.time.Duration.Companion.seconds


@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun StreamingScreen(entryKey: StreamingEntryKey) {
  val model = hiltViewModel<StreamingScreenModel, StreamingScreenModel.Factory>(
    creationCallback = { factory -> factory.create(entryKey) }
  )
  val context = LocalContext.current
  val activity = LocalActivity.current!! as ComponentActivity
  val density = LocalDensity.current
  val view = LocalView.current
  val backStack = LocalBackStack.current
  val configuration = LocalConfiguration.current
  val screenCornerRadii = rememberScreenCornerRadii()
  val displayCutoutInsets = rememberDisplayCutoutInsets()

  val coroutine = rememberCoroutineScope()
  val settingsOrNull by model.settingsStore.settings.collectAsState(null)
  val reminderOrNull by model.reminderStore.reminder.collectAsState(null)
  val maxFixedHeight = remember { InitRef(0.dp) }
  var streamViewUpdateKey by remember { mutableIntStateOf(0) }

  var streamConnection by remember { mutableStateOf<StreamConnection?>(null) }
  val inPipMode =
    remember(streamConnection) { streamConnection?.inPipModeFlow }?.collectAsState(false)?.value
      ?: false
  val streamConnectionStatus =
    streamConnection?.statusFlow?.collectAsState(StreamConnection.Status.INITIAL)?.value
      ?: StreamConnection.Status.INITIAL
  val performanceInfo =
    remember(streamConnection) { streamConnection?.performanceInfoFlow }?.collectAsState(null)?.value

  val currentStreamConnection by rememberUpdatedState(streamConnection)
  val currentSettingsOrNull by rememberUpdatedState(settingsOrNull)

  val gameInputHandler = remember(streamConnection, activity) {
    MouseInputByTouchesHandler(
      connection = streamConnection,
      inputCaptureProvider = null,
      inputGrabbed = { true },
      onToggleKeyboard = {
        activity.getSystemService(InputMethodManager::class.java)
          ?.toggleSoftInput(0, 0)
      },
    )
  }

  val settingsDrawerRef = remember { Ref<EdgeModalDrawerRef>() }

  OneTimeLaunchedEffect (Unit) {
    if (NetworkUtil.isUsingMobileData(context)) {
      toast(context, activity.getString(R.string.s_meter_network_warn))
      true
    } else {
      false
    }
  }

  val isCurrentScreen = backStack.lastOrNull() == entryKey
  DisposableEffect(activity, isCurrentScreen) {
    if (isCurrentScreen) {
      val previousRequestedOrientation = activity.requestedOrientation
      val window = activity.window
      val decorView = window.decorView
      val windowInsetsController = WindowCompat.getInsetsController(window, decorView)
      val previousSystemBarsBehavior = windowInsetsController.systemBarsBehavior
      val orientationJob = coroutine.launch {
        withFrameNanos { }
        withFrameNanos { }
        if (backStack.lastOrNull() == entryKey) {
          windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
          windowInsetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
          activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
      }

      onDispose {
        orientationJob.cancel()
        windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
        windowInsetsController.systemBarsBehavior = previousSystemBarsBehavior
        activity.requestedOrientation = previousRequestedOrientation
        decorView.postOnAnimation {
          ViewCompat.requestApplyInsets(decorView)
        }
      }
    } else {
      onDispose {}
    }
  }

  DisposableEffect(Unit) {
    // Make sure Wi-Fi is fully powered up
    val wifiMgr = activity.getSystemService(Context.WIFI_SERVICE) as WifiManager
    var highPerfWifiLock: WifiManager.WifiLock? = null
    var lowLatencyWifiLock: WifiManager.WifiLock? = null

    try {
      highPerfWifiLock =
        wifiMgr.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Starlight High Perf Lock")
      highPerfWifiLock.setReferenceCounted(false)
      highPerfWifiLock.acquire()

      lowLatencyWifiLock = wifiMgr.createWifiLock(
        WifiManager.WIFI_MODE_FULL_LOW_LATENCY,
        "Starlight Low Latency Lock"
      )
      lowLatencyWifiLock.setReferenceCounted(false)
      lowLatencyWifiLock.acquire()
    } catch (e: SecurityException) {
      // Some Samsung Galaxy S10+/S10e devices throw a SecurityException from
      // WifiLock.acquire() even though we have android.permission.WAKE_LOCK in our manifest.
      e.printStackTrace()
    }

    onDispose {
      highPerfWifiLock?.release()
      lowLatencyWifiLock?.release()
    }
  }

  LaunchedEffect(Unit) {
    view.requestUnbufferedDispatch(
      InputDevice.SOURCE_CLASS_BUTTON or // Keyboards
          InputDevice.SOURCE_CLASS_JOYSTICK or // Gamepads
          InputDevice.SOURCE_CLASS_POINTER or // Touchscreens and mice (w/o pointer capture)
          InputDevice.SOURCE_CLASS_POSITION or // Touchpads
          InputDevice.SOURCE_CLASS_TRACKBALL // Mice (pointer capture)
    )
  }

  LaunchedEffect(Unit) {
    val result = model.waitForComputerDetails()?.let { model.loadAppInfo() }
    if (result == null) {
      Globals.commonAlertDialog.show(
        CommonAlertDialogProps(
          content = { Text(stringResource(R.string.f_failed_to_load_app_info, entryKey.appName)) },
          onHide = { backStack.removeLastOrNull() }
        ))
    }
  }

  // SurfaceView(StreamView) will be re-created if app resumed, but composes AndroidView will not,
  // so we need to force re-create it by changing the key
  LifecycleResumeEffect(Unit) {
    if (streamConnection?.surfaceDestroyed != true) {
      streamViewUpdateKey++
    }
    onPauseOrDispose { }
  }

  LifecycleStartEffect(Unit) {
    onStopOrDispose {
      if (currentStreamConnection?.reportedCrash == false) {
        model.viewModelScope.launch {
          model.reminderStore.updateReminder { mediaCodecDecoderRendererCrashCount = 0 }
        }
      }
    }
  }

  DisposableEffect(Unit) {
    onDispose {
      if (
        currentSettingsOrNull?.enableLatencyToast == true &&
        currentStreamConnection?.statusFlow?.value == StreamConnection.Status.CONNECTED
      ) {
        currentStreamConnection?.showLatencyDialog(activity)
      }
    }
  }

  DisposableEffect(streamConnection) {
    onDispose { streamConnection?.release() }
  }

  LaunchedEffect(configuration) {
    streamConnection?.virtualController?.refreshLayout()
  }

  LaunchedEffect(streamConnection, settingsOrNull) {
    if (settingsOrNull?.disableWarnings == true) return@LaunchedEffect
    streamConnection?.notificationFlow?.collect {
      model.snackarHostState.showSnackbar(it)
    }
  }

  LaunchedEffect(streamConnectionStatus) {
    if (!listOf(
        StreamConnection.Status.TERMINATED,
        StreamConnection.Status.FAILED
      ).contains(streamConnectionStatus)
    ) return@LaunchedEffect

    val errorMessage = streamConnection?.errorMessage
    Globals.commonAlertDialog.show(
      CommonAlertDialogProps(
        title = errorMessage?.title ?: activity.getString(R.string.notice),
        content = { Text(errorMessage?.content ?: stringResource(R.string.s_conn_terminated_msg)) },
        onHide = { backStack.removeLastOrNull() }
      ))
  }

  fun initializeStreamConnection(
    streamView: StreamView,
    reminder: ReminderStoreData,
    settings: SettingsStoreData
  ) {
    streamConnection = StreamConnection(
      model.computerDetails!!,
      model.appInfo!!,
      streamView,
      reminder,
      settings,
      model.reminderStore,
      activity,
      coroutine
    )
    streamConnection!!.streamView.requestUnbufferedDispatch(
      InputDevice.SOURCE_CLASS_BUTTON or // Keyboards
          InputDevice.SOURCE_CLASS_JOYSTICK or // Gamepads
          InputDevice.SOURCE_CLASS_POINTER or // Touchscreens and mice (w/o pointer capture)
          InputDevice.SOURCE_CLASS_POSITION or // Touchpads
          InputDevice.SOURCE_CLASS_TRACKBALL // Mice (pointer capture)
    )
    try {
      streamConnection!!.initialize()
    } catch (e: StreamConnection.AvcUnsupportedException) {
      Timber.e(e)
      Globals.commonAlertDialog.show(
        CommonAlertDialogProps(
          onHide = { backStack.removeLastOrNull() },
          content = { Text(stringResource(R.string.s_decoder_h264_unsupported)) }
        ))
    }
  }

  fun initializeVirtualController(frameLayout: FrameLayout, context: Context) {
    streamConnection!!.virtualController =
      VirtualController(streamConnection!!.controllerHandler, frameLayout, context).also {
        it.refreshLayout()
        it.show()
      }
  }

  val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

  if (!landscape) {
    return Spacer(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
  }

  val settings = settingsOrNull ?: return
  val reminder = reminderOrNull ?: return

  val videoResolution =
    VideoResolutions.match(activity, settings.width, settings.height) ?: VideoResolutions.P1080


  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(Color.Black)
      .imePadding(),
    contentAlignment = if (videoResolution == VideoResolutions.NATIVE) Alignment.TopEnd else Alignment.Center
  ) {
    BoxWithConstraints(
      modifier = Modifier
        .let { if (WindowInsets.isImeVisible) it.verticalScroll(rememberScrollState()) else it },
      propagateMinConstraints = true
    ) {
      if (maxHeight != Dp.Infinity) {
        maxFixedHeight.value = max(maxFixedHeight.value.value, maxHeight.value).dp
      }

      val streamViewWidth =
        if (listOf(VideoResolutions.NATIVE, VideoResolutions.NATIVE_FULL_SCREEN).contains(
            videoResolution
          )
        )
          with(density) { settings.width.toDp() }
        else
          maxFixedHeight.value * 16 / 9

      // Stream view
      if (model.computerDetails != null && model.appInfo != null) {
        key(streamViewUpdateKey) {
          AndroidView(
            modifier = Modifier
              .width(streamViewWidth)
              .height(maxFixedHeight.value)
              .let {
                if (!settings.onscreenController) it.mouseInputByTouches(
                  gameInputHandler,
                  settings.absoluteMouseMode,
                  settings.mouseNavButtons
                ) else it
              }
              .keyboardInput(streamConnection?.inputCaptureListener)
              .autoFocus()
              .focusable(),
            factory = {
              StreamView(it).also { streamView ->
                initializeStreamConnection(
                  streamView,
                  reminder,
                  settings
                )
              }
            }
          )
        }
      }
    }

    // On trackpad mode, we need listen touch events on whole screen contained black areas on both sides
    if (settings.touchscreenTrackpad && !WindowInsets.isImeVisible && !settings.onscreenController) {
      Spacer(
        modifier = Modifier
          .fillMaxSize()
          .mouseInputByTouches(
            gameInputHandler,
            settings.absoluteMouseMode,
            settings.mouseNavButtons
          )
      )
    }

    // VirtualController
    if (
      !inPipMode &&
      settings.onscreenController &&
      streamConnectionStatus == StreamConnection.Status.CONNECTED &&
      !WindowInsets.isImeVisible
    ) {
      AndroidView(
        modifier = Modifier
          .windowInsetsPadding(WindowInsets.displayCutout)
          .fillMaxSize(),
        factory = { FrameLayout(it).also { initializeVirtualController(it, it.context) } }
      )
    }

    // Button to show IME
    if (model.sessionSettings.imeButtonShown && !inPipMode) {
      IconButton(
        modifier = Modifier
          .align(Alignment.TopStart)
          .absoluteOffset(
            x = with(density) { (displayCutoutInsets?.safeInsetLeft ?: 0).toDp() },
            y = with(density) { (displayCutoutInsets?.safeInsetTop ?: 0).toDp() } +
              if (settings.onscreenController) 25.dp else 0.dp
          ),
        onClick = {
          activity.getSystemService(InputMethodManager::class.java)
            ?.toggleSoftInput(0, 0)
        }
      ) {
        Icon(
          modifier = Modifier
            .size(40.dp),
          imageVector = Icons.Rounded.Keyboard,
          contentDescription = null,
          tint = Color.White.copy(alpha = 0.25f)
        )
      }
    }

    if (
      model.sessionSettings.functionKeysShown &&
      streamConnectionStatus == StreamConnection.Status.CONNECTED &&
      !inPipMode &&
      !WindowInsets.isImeVisible
    ) {
      VirtualFunctionKeys(
        modifier = Modifier
          .align(Alignment.TopEnd)
          .offset(y = with(density) { (screenCornerRadii.getOrNull(1) ?: 0).toDp() }),
        onKeyClick = { keyCode ->
          streamConnection?.inputCaptureListener?.sendPairedKeyInput(keyCode)
        }
      )
    }

    if (settings.enablePerfOverlay && performanceInfo != null && !inPipMode && !WindowInsets.isImeVisible) {
      PerformanceOverlay(
        performanceInfo = performanceInfo,
      )
    }

    if (streamConnectionStatus == StreamConnection.Status.CONNECTED && !inPipMode && !WindowInsets.isImeVisible) {
      SettingsDrawerButton { settingsDrawerRef.value?.open() }

      SettingsDrawer(
        sessionSettings = model.sessionSettings,
        inputCaptureListener = streamConnection!!.inputCaptureListener,
        onSettingsChanged = { streamViewUpdateKey++ },
        onSessionSettingsChanged = { model.sessionSettings = it },
        ref = settingsDrawerRef
      )
    }

    // Mask when loading
    AnimatedVisibility(
      visible = listOf(
        StreamConnection.Status.INITIAL,
        StreamConnection.Status.INITIALIZED,
      ).contains(streamConnectionStatus),
      enter = EnterTransition.None,
      exit = fadeOut()
    ) {
      Column(
        modifier = Modifier
          .fillMaxSize()
          .background(MaterialTheme.colorScheme.background),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
      ) {
        BoxWithConstraints {
          ContainedLoadingIndicator(
            modifier = Modifier.size(minOf(this.maxWidth, this.maxHeight) * 0.3f),
          )
        }

        Text(
          modifier = Modifier
            .padding(top = 20.dp)
            .fillMaxWidth(0.75f),
          style = if (!inPipMode) MaterialTheme.typography.displaySmall else MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onBackground,
          textAlign = TextAlign.Center,
          overflow = TextOverflow.Ellipsis,
          text = entryKey.appName
        )
      }
    }

    Box(
      modifier = Modifier
        .padding(15.dp)
        .fillMaxSize(),
      contentAlignment = Alignment.TopStart
    ) {
      SnackbarHost(model.snackarHostState)
    }
  }
}

@Composable
private fun BoxScope.SettingsDrawerButton(
  onClick: () -> Unit
) {
  var drawerButtonFaded by remember { mutableStateOf(false) }
  LaunchedEffect(drawerButtonFaded) {
    if (drawerButtonFaded) return@LaunchedEffect
    delay(3.seconds)
    drawerButtonFaded = true
  }
  val animatedDrawerButtonFaded by animateFloatAsState(if (drawerButtonFaded) 1f else 0f)
  IconButton(
    modifier = Modifier
      .align(Alignment.CenterEnd)
      .offset {
        density.run {
          IntOffset(
            x = (20.dp * animatedDrawerButtonFaded).toPx().fastRoundToInt(),
            y = -(40).dp.toPx().fastRoundToInt()
          )
        }
      }
      .border(1.dp, MaterialTheme.colorScheme.primary, CircleShape)
      .alpha(1f - (animatedDrawerButtonFaded * 0.5f)),
    onClick = {
      drawerButtonFaded = false
      onClick()
    }
  ) {
    Icon(
      modifier = Modifier
        .size(30.dp),
      imageVector = Icons.Rounded.MenuOpen,
      contentDescription = null,
      tint = Color.White
    )
  }
}