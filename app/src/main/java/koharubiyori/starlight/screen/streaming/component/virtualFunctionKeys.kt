package koharubiyori.starlight.screen.streaming.component

import android.view.KeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.SmartButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastRoundToInt
import koharubiyori.starlight.util.vibrate
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds

@Composable
fun VirtualFunctionKeys(
  modifier: Modifier = Modifier,
  onKeyClick: (keyCode: Int) -> Unit
) {
  val context = LocalContext.current
  val density = LocalDensity.current
  var expanded by remember { mutableStateOf(false) }
  var faded by remember { mutableStateOf(false) }

  LaunchedEffect(faded) {
    if (faded) return@LaunchedEffect
    delay(3.seconds)
    faded = true
  }

  val animatedFaded by animateFloatAsState(if (faded && !expanded) 1f else 0f)
  Row(
    modifier = modifier,
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp)
  ) {
    AnimatedVisibility(
      visible = expanded,
      enter = expandHorizontally(expandFrom = Alignment.End),
      exit = shrinkHorizontally(shrinkTowards = Alignment.End)
    ) {
      Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        (KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12).forEach { keyCode ->
          Surface(
            modifier = Modifier
              .width(40.dp)
              .height(40.dp),
            shape = MaterialTheme.shapes.small,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
            color = Color.Black.copy(alpha = 0.65f),
            onClick = {
              onKeyClick(keyCode)
              vibrate(context)
            },
          ) {
            Box(contentAlignment = Alignment.Center) {
              Text(
                text = "F${keyCode - KeyEvent.KEYCODE_F1 + 1}",
                color = Color.White,
                style = MaterialTheme.typography.labelMedium
              )
            }
          }
        }
      }
    }

    Box {
      Surface(
        modifier = Modifier
          .width(80.dp)
          .height(40.dp)
          .alpha(1f - animatedFaded * 0.5f)
          .offset { IntOffset(x = density.run { (40 * animatedFaded).dp.toPx().fastRoundToInt() }, y = 0) },
        shape = MaterialTheme.shapes.small,
        color = Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
        onClick = {
          faded = false
          expanded = !expanded
        },
      ) {
        Box(contentAlignment = Alignment.Center) {
          Icon(
            imageVector = Icons.Rounded.SmartButton,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(26.dp)
          )
        }
      }
    }
  }
}