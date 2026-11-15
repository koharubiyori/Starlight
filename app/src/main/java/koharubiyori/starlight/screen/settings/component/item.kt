package koharubiyori.starlight.screen.settings.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SettingsScreenItem(
  title: String,
  titleStyle: TextStyle = TextStyle(),
  subtext: String? = null,
  enabled: Boolean = true,
  onClick: (() -> Unit)? = null,
  rightContent: (@Composable () -> Unit)? = null,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(enabled = enabled) { onClick?.invoke() }
      .padding(vertical = 7.5.dp, horizontal = 20.dp)
      .alpha(if (enabled) 1f else 0.3f),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
  ) {
    Column(
      modifier = Modifier
        .weight(1f),
    ) {
      Text(
        text = title,
        style = titleStyle,
        fontSize = 16.sp
      )
      if (subtext != null) {
        Text(
          modifier = Modifier
            .padding(top = 3.dp),
          text = subtext,
          fontSize = 14.sp,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          lineHeight = 16.sp
        )
      }
    }

    Box(
      modifier = Modifier
        .padding(start = 20.dp)
    ) {
      rightContent?.invoke()
    }
  }
}