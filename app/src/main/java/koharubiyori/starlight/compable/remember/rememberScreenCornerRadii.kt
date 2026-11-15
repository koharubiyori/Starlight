package koharubiyori.starlight.compable.remember

import android.os.Build
import android.view.RoundedCorner
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.core.view.ViewCompat

@Composable
fun rememberScreenCornerRadii(): List<Int?> {
    val view = LocalView.current
    var radii by remember(view) {
        mutableStateOf(List<Int?>(4) { null })
    }

    DisposableEffect(view) {
        val listener = View.OnApplyWindowInsetsListener { v, insets ->
            radii = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                listOf(
                    insets.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius,
                    insets.getRoundedCorner(RoundedCorner.POSITION_TOP_RIGHT)?.radius,
                    insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)?.radius,
                    insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)?.radius
                )
            } else {
                List(4) { null }
            }

            v.onApplyWindowInsets(insets)
        }

        view.setOnApplyWindowInsetsListener(listener)
        ViewCompat.requestApplyInsets(view)

        onDispose {
            view.setOnApplyWindowInsetsListener(null)
        }
    }

    return radii
}