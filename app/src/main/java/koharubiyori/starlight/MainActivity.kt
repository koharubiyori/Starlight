package koharubiyori.starlight

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import dagger.hilt.android.AndroidEntryPoint
import koharubiyori.starlight.compable.ActivityEvents
import koharubiyori.starlight.compable.MutableActivityEvents
import koharubiyori.starlight.initialization.OnComposeWillCreate
import koharubiyori.starlight.initialization.onActivityCreate
import koharubiyori.starlight.nav.Routes
import koharubiyori.starlight.theme.RootTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
  val activityEventFlows = MutableActivityEvents()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    onActivityCreate()

    @Composable
    fun ContentBody() {
      ActivityEvents.Provide(activityEventFlows) {
        RootTheme {
          OnComposeWillCreate {
            Routes(intent)
          }
        }
      }
    }

    setContent { ContentBody() }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    activityEventFlows.newIntent.tryEmit(intent)
  }
}

