package koharubiyori.starlight.compable.remember

import android.view.DisplayCutout
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration

@Composable
fun rememberDisplayCutoutInsets(): DisplayCutout? {
  val activity = LocalActivity.current!!
  val orientation = LocalConfiguration.current.orientation
  return remember(activity, orientation) {
    activity.windowManager.maximumWindowMetrics.windowInsets.displayCutout
  }
}
