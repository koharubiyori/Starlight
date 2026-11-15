package koharubiyori.starlight.initialization

import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import koharubiyori.starlight.screen.settings.defaultBitrates
import koharubiyori.starlight.screen.settings.isEmpty
import koharubiyori.starlight.store.SettingsStore
import koharubiyori.starlight.util.updateLocale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.Locale

fun ComponentActivity.onActivityCreate() {
  enableEdgeToEdge()
  val settingsStore = SettingsStore(this)
  val (settings, storedSettings) = runBlocking {
    settingsStore.settings.first() to settingsStore.storedSettings.first()
  }
  if (storedSettings.bitrates.isEmpty()) {
    runBlocking {
      settingsStore.updateSettings {
        bitrates = defaultBitrates(this@onActivityCreate, settings.fps)
      }
    }
  }
  if (settings.language != "auto") {
    updateLocale(Locale.forLanguageTag(settings.language))
  }
}
