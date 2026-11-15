package koharubiyori.starlight.component.styled
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun StyledCircularProgressIndicator(
  modifier: Modifier = Modifier,

) {
  val themeColors = MaterialTheme.colorScheme

  CircularProgressIndicator(
    modifier = modifier,
    color = themeColors.primary
  )
}