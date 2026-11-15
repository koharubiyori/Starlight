package koharubiyori.starlight.compable

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.CoroutineScope

@Composable
fun OneTimeLaunchedEffect(
  vararg key: Any,
  block: suspend CoroutineScope.() -> Boolean,
) {
  var runned by rememberSaveable { mutableStateOf(false) }

  LaunchedEffect(*key) {
    if (!runned) {
      if (block()) runned = true
    }
  }
}