package koharubiyori.starlight.screen.appList

import android.app.Activity
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import koharubiyori.starlight.Globals
import koharubiyori.starlight.R
import koharubiyori.starlight.component.commonDialog.ButtonConfig
import koharubiyori.starlight.component.commonDialog.CommonAlertDialogProps
import koharubiyori.starlight.nvstream.AndroidCryptoProvider
import koharubiyori.starlight.nvstream.http.HostHttpResponseException
import koharubiyori.starlight.nvstream.http.NvApp
import koharubiyori.starlight.nvstream.http.NvHTTP
import koharubiyori.starlight.screen.appList.util.AppCoverManager
import koharubiyori.starlight.screen.appList.util.isPlaceholderImage
import koharubiyori.starlight.screen.appList.util.loadCachedAppList
import koharubiyori.starlight.screen.home.util.ComputerDetailsEx
import koharubiyori.starlight.screen.home.util.ComputerDetector.Companion.getLatest
import koharubiyori.starlight.screen.home.util.ComputersPoller
import koharubiyori.starlight.util.LoadStatus
import koharubiyori.starlight.util.ShortcutHelper
import koharubiyori.starlight.util.createNvHttp
import koharubiyori.starlight.util.debugPrint
import koharubiyori.starlight.util.toast
import koharubiyori.starlight.util.uniqueId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParserException
import timber.log.Timber
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

@HiltViewModel(assistedFactory = AppListScreenModel.Factory::class)
class AppListScreenModel @AssistedInject constructor(
  @Assisted val entryKey: AppListEntryKey,
  @ApplicationContext private val context: Context,
  val computersPoller: ComputersPoller
) : ViewModel() {
  var latestComputerDetails by mutableStateOf<ComputerDetailsEx?>(null)
  var appList by mutableStateOf(emptyList<NvApp>())
  var coverImgMap by mutableStateOf(emptyMap<Int, Bitmap?>())
  var loadStatus by mutableStateOf(LoadStatus.INITIAL)

  suspend fun waitForComputerDetails(): ComputerDetailsEx? {
    return latestComputerDetails ?: run {
      computersPoller.start()
      computersPoller.waitForActiveComputerByUuid(entryKey.computerUuid, 10.seconds).also {
        computersPoller.stop()
        latestComputerDetails = it
      }
    }
  }

  suspend fun updateComputerDetails() {
    latestComputerDetails?.let { latestComputerDetails = it.getLatest(context) }
  }

  suspend fun loadAppList(activity: Activity, withoutCache: Boolean = true) = withContext(Dispatchers.IO) {
    loadStatus = LoadStatus.LOADING
    if (waitForComputerDetails() == null) {
      loadStatus = LoadStatus.FAILED
      toast(context, activity.getString(R.string.failed_to_load))
      return@withContext
    }

    if (!withoutCache) updateComputerDetails()

    loadCachedAppList(context, latestComputerDetails!!, withoutCache).collect {
      appList = it
      loadStatus = LoadStatus.DONE

      appList.map {
        launch {
          val coverImg = AppCoverManager.loadCover(context, latestComputerDetails!!, it)
          withContext(Dispatchers.Main) {
            coverImgMap = coverImgMap.toMutableMap().apply {
              this[it.appId] = coverImg
            }
          }
        }
      }.joinAll()
    }
  }

  suspend fun stopApp(activity: Activity, appName: String) = withContext(Dispatchers.IO) {
    val nvHttp = createNvHttp(context, latestComputerDetails!!.getLatest(context))
    val result = nvHttp.quitApp()
    updateComputerDetails()
    toast(
      context, activity.getString(
        if (result) R.string.f_successfully_quit_app else R.string.f_failed_to_quit_app,
        appName
      )
    )
  }

  suspend fun createShortcut(activity: Activity, app: NvApp) {
    val appCover = AppCoverManager.loadCover(context, latestComputerDetails!!, app)?.let { if (it.isPlaceholderImage()) null else it }

    Globals.commonLoadingDialog.showText(activity.getString(R.string.creating))
    val result = ShortcutHelper.requestPinAppShortcut(
      context,
      entryKey.computerUuid + app.appId,
      entryKey.computerUuid,
      app.appId,
      app.appName,
      appCover
    )
    Globals.commonLoadingDialog.hide()
    if (result) {
      toast(context, activity.getString(R.string.created_shortcut_successfully))
    } else {
      Globals.commonAlertDialog.show(CommonAlertDialogProps(
        content = { Text(stringResource(R.string.s_failed_to_create_shortcut)) },
        leftButton = ButtonConfig(
          text = activity.getString(R.string.go_to_settings),
          onClick = {
            val intent = Intent(
              Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
              Uri.fromParts("package", context.packageName, null)
            )
            activity.startActivity(intent)
          }
        )
      ))
    }
  }

  @AssistedFactory
  interface Factory {
    fun create(key: AppListEntryKey): AppListScreenModel
  }
}
