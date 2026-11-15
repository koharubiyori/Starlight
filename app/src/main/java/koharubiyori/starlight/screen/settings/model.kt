package koharubiyori.starlight.screen.settings

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import koharubiyori.starlight.store.ReminderStore
import koharubiyori.starlight.store.SettingsStore
import javax.inject.Inject

@HiltViewModel
class SettingsScreenModel @Inject constructor(
  val settingsStore: SettingsStore,
): ViewModel() {

}