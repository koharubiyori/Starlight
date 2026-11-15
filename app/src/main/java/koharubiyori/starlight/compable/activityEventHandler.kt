package koharubiyori.starlight.compable

import android.app.Activity
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

@Composable
fun ActivityEventHandler(
  onNewIntent: ((Intent) -> Unit)? = null,
) {
  val activityEvents = LocalActivityEvents.current

  LaunchedEffect(activityEvents) {
    launch { activityEvents.newIntent.collect { onNewIntent?.invoke(it) } }
  }
}

class MutableActivityEvents(
  override val newIntent: MutableSharedFlow<Intent> = MutableSharedFlow(3),
) : ActivityEvents(newIntent)

abstract class ActivityEvents(
  open val newIntent: SharedFlow<Intent>,
) {
  companion object {
    @Composable
    fun Provide(value: ActivityEvents, content: @Composable () -> Unit) {
      CompositionLocalProvider(
        LocalActivityEvents provides value,
        content = content
      )
    }
  }
}

private val LocalActivityEvents = staticCompositionLocalOf<ActivityEvents> { error("ActivityEvents not provided") }