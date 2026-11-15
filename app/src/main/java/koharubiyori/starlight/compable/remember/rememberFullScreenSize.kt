package koharubiyori.starlight.compable.remember

import android.view.WindowManager
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberFullScreenSize(): Size {
  val configuration = LocalConfiguration.current
  val context = LocalContext.current

  return remember(configuration.orientation) {
    val windowBounds = context.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
    Size(windowBounds.width().toFloat(), windowBounds.height().toFloat())
  }
}
