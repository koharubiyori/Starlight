package koharubiyori.starlight.compable

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.compose.LifecycleResumeEffect

@Composable
fun StatusBar(
  mode: StatusBarMode = StatusBarMode.VISIBLE,
  backgroundColor: Color = Color.Transparent,
  darkIcons: Boolean = false,
) {
  val refStatusBarLocked = statusBarLocked
  val activity = LocalActivity.current!!

  fun syncConfig() {
    val window = activity.window
    val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)

    when(mode) {
      StatusBarMode.VISIBLE -> {
        windowInsetsController.show(WindowInsetsCompat.Type.statusBars())
      }
      StatusBarMode.HIDE -> windowInsetsController.hide(WindowInsetsCompat.Type.statusBars())
    }

    windowInsetsController.isAppearanceLightStatusBars = darkIcons

    CachedStatusBarConfig.mode = mode
    CachedStatusBarConfig.backgroundColor = backgroundColor
    CachedStatusBarConfig.darkIcons = darkIcons
  }

  LaunchedEffect(
    mode,
    backgroundColor,
    darkIcons,
    refStatusBarLocked
  ) {
    if (refStatusBarLocked) return@LaunchedEffect
    syncConfig()
  }

  LifecycleResumeEffect(refStatusBarLocked) {
    if (!refStatusBarLocked) syncConfig()
    onPauseOrDispose {  }
  }
}

var statusBarLocked by mutableStateOf(false)
object CachedStatusBarConfig {
  var mode = StatusBarMode.VISIBLE
  var backgroundColor = Color.Transparent
  var darkIcons = false
}

enum class StatusBarMode {
  VISIBLE,
  HIDE,
}
