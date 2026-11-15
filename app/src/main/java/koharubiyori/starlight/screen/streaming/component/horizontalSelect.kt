package koharubiyori.starlight.screen.streaming.component

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

@Composable
fun <T> HorizontalSelect(
  modifier: Modifier = Modifier,
  options: List<HorizontalSelectOption<T>>,
  selectedOption: T,
  onOptionSelected: (T) -> Unit,
) {
  require(options.isNotEmpty()) { "HorizontalSelect requires at least one option." }
  val selectedIndex = options.indexOfFirst { it.value == selectedOption }.coerceAtLeast(0)

  BoxWithConstraints(
    modifier = modifier
      .height(50.dp)
      .clip(MaterialTheme.shapes.small)
      .background(Color(0xFF1E1E1E))
  ) {
    val itemWidth = maxWidth / options.size
    val indicatorOffset by animateDpAsState(
      targetValue = itemWidth * selectedIndex,
      animationSpec = spring(dampingRatio = 0.72f, stiffness = 420f),
      label = "horizontalSelectIndicatorOffset"
    )

    Box(
      modifier = Modifier
        .offset {
          IntOffset(
            x = (indicatorOffset + 4.dp).roundToPx(),
            y = 4.dp.roundToPx()
          )
        }
        .width(itemWidth - 8.dp)
        .height(maxHeight - 8.dp)
        .clip(MaterialTheme.shapes.small)
        .background(MaterialTheme.colorScheme.primary)
    )

    Row(modifier = Modifier.fillMaxSize()) {
      options.forEach { option ->
        Box(
          modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .clickable { onOptionSelected(option.value) },
          contentAlignment = Alignment.Center
        ) {
          CompositionLocalProvider(
            LocalTextStyle provides MaterialTheme.typography.bodySmallEmphasized.copy(
              color = MaterialTheme.colorScheme.onPrimary,
              textAlign = TextAlign.Center
            )
          ) { option.content() }
        }
      }
    }
  }
}

class HorizontalSelectOption<T>(
  val value: T,
  val content: @Composable () -> Unit,
) {
  constructor(value: T, label: String) : this(content = { Text(label) }, value = value)
}
