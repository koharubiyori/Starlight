package koharubiyori.starlight.screen.home

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import koharubiyori.starlight.Globals
import koharubiyori.starlight.R
import koharubiyori.starlight.component.commonDialog.ButtonConfig
import koharubiyori.starlight.component.commonDialog.CommonAlertDialogProps
import koharubiyori.starlight.nvstream.AndroidCryptoProvider
import koharubiyori.starlight.nvstream.http.ComputerDetails
import koharubiyori.starlight.nvstream.http.NvHTTP
import koharubiyori.starlight.nvstream.http.PairingManager
import koharubiyori.starlight.screen.home.component.DeviceActionsBottomSheetState
import koharubiyori.starlight.screen.home.util.ComputerDetailsEx
import koharubiyori.starlight.screen.home.util.ComputerDetector.Companion.getLatest
import koharubiyori.starlight.screen.home.util.ComputersPoller
import koharubiyori.starlight.store.PairedComputerStore
import koharubiyori.starlight.util.NetworkUtil
import koharubiyori.starlight.util.ShortcutHelper
import koharubiyori.starlight.util.createNvHttp
import koharubiyori.starlight.util.toast
import koharubiyori.starlight.util.uniqueId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParserException
import java.io.FileNotFoundException
import java.io.IOException
import java.net.UnknownHostException
import javax.inject.Inject

@HiltViewModel
class HomeScreenModel @Inject constructor(
  val pairedComputerStore: PairedComputerStore,
  @ApplicationContext private val context: Context,
  val computersPoller: ComputersPoller,
) : ViewModel() {
  val deviceActionsBottomSheetState = DeviceActionsBottomSheetState()

  fun getComputerFlowByUuid(uuid: String): Flow<ComputerDetailsEx?> {
    return computersPoller.computersFlow.map { it.firstOrNull { it.uuid == uuid } }
  }

  suspend fun pair(activity: Activity, computeDetails: ComputerDetailsEx): Boolean = withContext(Dispatchers.IO) {
    Globals.commonLoadingDialog.showText(activity.getString(R.string.pairing))
    val latestDetails = computeDetails.getLatest(context)
    if (latestDetails.state == ComputerDetails.State.OFFLINE || latestDetails.activeAddress == null) {
      Globals.commonLoadingDialog.hide()
      toast(context, activity.getString(R.string.s_offline_computer))
      return@withContext false
    }

    var message: String?

    try {
      computersPoller.stop()
      val nvHttp = createNvHttp(context, latestDetails)
      Globals.commonLoadingDialog.hide()

      if (nvHttp.pairState == PairingManager.PairState.PAIRED) return@withContext true

      val pinStr = PairingManager.generatePinString()
      showPairingPinDialog(activity, pinStr)

      val pairState = nvHttp.pairingManager.pair(nvHttp.getServerInfo(true), pinStr)
      Globals.commonAlertDialog.hide()

      if (pairState == PairingManager.PairState.PAIRED) {
        latestDetails.serverCert = nvHttp.pairingManager.pairedCert
        pairedComputerStore.updateComputer(latestDetails.getLatest(context))

        toast(context, activity.getString(R.string.paired_successfully))
        return@withContext true
      }

      message = when (pairState) {
        PairingManager.PairState.PIN_WRONG -> {
          activity.getString(R.string.wrong_pin)
        }
        PairingManager.PairState.FAILED -> {
          if (latestDetails.runningGameId != 0) {
            activity.getString(R.string.s_failed_paring_for_computer_in_game)
          } else {
            activity.getString(R.string.pairing_failed)
          }
        }
        PairingManager.PairState.ALREADY_IN_PROGRESS -> {
          activity.getString(R.string.s_pairing_in_progress)
        }
        else -> null
      }
    } catch (ex: Exception) {
      Globals.commonLoadingDialog.hide()
      Globals.commonAlertDialog.hide()
      message = when (ex) {
        is UnknownHostException -> activity.getString(R.string.s_error_unknown_host)
        is FileNotFoundException -> activity.getString(R.string.s_error_404)
        is XmlPullParserException, is IOException -> ex.toString()
        else -> throw ex
      }
    } finally {
      computersPoller.start()
    }

    message?.let { Globals.commonAlertDialog.showText(it) }

    return@withContext false
  }

  // There is not the method named as 'unpair' in the official sunshine's NvHttp. We may need to add it in our sunshine app.
  suspend fun unpair(activity: Activity, computeDetails: ComputerDetailsEx) = withContext(Dispatchers.IO) {
    pairedComputerStore.removeComputer(computeDetails.uuid)
    toast(context, activity.getString(R.string.unpaired_successfully))
    computersPoller.restartPollingJob()

//          computersPoller.restartPollingJob()
  //    Globals.commonLoadingDialog.showText(activity.getString(R.string.unpairing))
//    val latestDetails = computeDetails.getLatest(context)
//    val message = try {
//      val nvHttp = NvHTTP(
//        latestDetails.activeAddress,
//        latestDetails.httpsPort, uniqueId(context), latestDetails.serverCert,
//        AndroidCryptoProvider(context)
//      )
//      if (nvHttp.pairState === PairingManager.PairState.PAIRED) {
//        nvHttp.unpair()
//        if (nvHttp.pairState === PairingManager.PairState.NOT_PAIRED) {
//          pairedComputerStore.removeComputer(latestDetails.uuid)
//          computersPoller.restartPollingJob()
//          activity.getString(R.string.unpaired_successfully)
//        } else {
//          activity.getString(R.string.unpair_fail)
//        }
//      } else {
//        activity.getString(R.string.unpair_error)
//      }
//    } catch (e: UnknownHostException) {
//      activity.getString(R.string.error_unknown_host)
//    } catch (e: FileNotFoundException) {
//      activity.getString(R.string.error_404)
//    } catch (e: XmlPullParserException) {
//      e.printStackTrace()
//      activity.getString(R.string.error_xml_parse)
//    } catch (e: IOException) {
//      e.printStackTrace()
//      activity.getString(R.string.error_io)
//    } finally {
//      Globals.commonLoadingDialog.hide()
//    }
//
//    toast(context, message)
  }

  suspend fun wakeOnLan(activity: Activity, computerDetails: ComputerDetailsEx) = coroutineScope {
    if (computerDetails.macAddress == null) {
      Globals.commonAlertDialog.showText(activity.getString(R.string.s_wol_no_mac))
      return@coroutineScope
    }

    listOfNotNull(
      computerDetails.activeAddress,
      computerDetails.localAddress,
      computerDetails.manualAddress,
      computerDetails.remoteAddress
    )
      .map { it.address }
      .plus("255.255.255.255")
      .map {
        launch { NetworkUtil.sendWakeOnLan(it, computerDetails.macAddress, 7) }
        launch { NetworkUtil.sendWakeOnLan(it, computerDetails.macAddress, 9) }
      }
      .joinAll()

    toast(context, activity.getString(R.string.woke))
  }

  suspend fun createShortcut(activity: Activity, computerDetails: ComputerDetailsEx) {
    Globals.commonLoadingDialog.showText(activity.getString(R.string.creating))
    val result = ShortcutHelper.requestPinComputerShortcut(
      context,
      computerDetails.uuid,
      computerDetails.uuid,
      computerDetails.name,
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

  override fun onCleared() {
    computersPoller.stop()
  }
}

private fun showPairingPinDialog(activity: Activity, pin: String) {
  Globals.commonAlertDialog.show(CommonAlertDialogProps(
    title = activity.getString(R.string.pairing),
    hideOnDismiss = false,
    content = {
      Column(
        modifier = Modifier
          .fillMaxWidth()
      ) {
        Text(stringResource(R.string.s_enter_pairing_pin_msg))
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp),
          horizontalArrangement = Arrangement.SpaceEvenly
        ) {
          for (char in pin) {
            Text(
              fontSize = 35.sp,
              fontWeight = FontWeight.Bold,
              text = char.toString()
            )
          }
        }
      }
    }
  ))
}
