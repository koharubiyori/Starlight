package koharubiyori.starlight.screen.streaming

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import koharubiyori.starlight.nvstream.http.HostHttpResponseException
import koharubiyori.starlight.nvstream.http.NvApp
import koharubiyori.starlight.screen.home.util.ComputerDetailsEx
import koharubiyori.starlight.screen.home.util.ComputersPoller
import koharubiyori.starlight.screen.streaming.component.SessionSettings
import koharubiyori.starlight.store.ReminderStore
import koharubiyori.starlight.store.SettingsStore
import koharubiyori.starlight.util.createNvHttp
import koharubiyori.starlight.util.debugPrint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParserException
import timber.log.Timber
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

@HiltViewModel(assistedFactory = StreamingScreenModel.Factory::class)
class StreamingScreenModel @AssistedInject constructor(
  @Assisted val entryKey: StreamingEntryKey,
  @ApplicationContext private val context: Context,
  val reminderStore: ReminderStore,
  val settingsStore: SettingsStore,
  val computersPoller: ComputersPoller
): ViewModel() {
  val snackarHostState = SnackbarHostState()
  var computerDetails by mutableStateOf<ComputerDetailsEx?>(null)
  var appInfo by mutableStateOf<NvApp?>(null)
  var sessionSettings by mutableStateOf(SessionSettings())

  suspend fun waitForComputerDetails(): ComputerDetailsEx? {
    return computerDetails ?: run {
      computersPoller.start()
      computersPoller.waitForActiveComputerByUuid(entryKey.computerUuid, 10.seconds).also {
        computersPoller.stop()
        computerDetails = it
      }
    }
  }

  suspend fun loadAppInfo(): NvApp? = withContext(Dispatchers.IO) {
    val appList = try {
      val nvHttp = createNvHttp(context, computerDetails!!)
      nvHttp.appList
    } catch (ex: Exception) {
      when (ex) {
        is HostHttpResponseException,
        is IOException,
        is XmlPullParserException -> {
          Timber.e(ex, "Failed to load the app info")
          null
        }
        else -> throw ex
      }
    }

    appList?.find { it.appId == entryKey.appId }.also { appInfo = it }
  }

  @AssistedFactory
  interface Factory {
    fun create(key: StreamingEntryKey): StreamingScreenModel
  }
}
