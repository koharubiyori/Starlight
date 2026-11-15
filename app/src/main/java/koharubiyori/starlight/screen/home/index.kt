package koharubiyori.starlight.screen.home

import androidx.activity.compose.LocalActivity
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.KeyOff
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import koharubiyori.starlight.R
import koharubiyori.starlight.component.EmptyContent
import koharubiyori.starlight.component.TopAppBarIcon
import koharubiyori.starlight.component.animation.AnimatedAppearing
import koharubiyori.starlight.component.styled.StyledPullToRefreshBox
import koharubiyori.starlight.component.styled.StyledTopAppBar
import koharubiyori.starlight.component.styled.TopAppBarTitle
import koharubiyori.starlight.nav.LocalBackStack
import koharubiyori.starlight.nav.NavEntryKey
import koharubiyori.starlight.nvstream.http.PairingManager
import koharubiyori.starlight.screen.appList.AppListEntryKey
import koharubiyori.starlight.screen.home.component.DeviceActionsBottomSheet
import koharubiyori.starlight.screen.home.util.ComputerDetailsEx
import koharubiyori.starlight.screen.settings.SettingsEntryKey
import koharubiyori.starlight.util.toast
import kotlinx.coroutines.launch

object HomeEntryKey : NavEntryKey

@Composable
fun HomeScreen() {
  val model = hiltViewModel<HomeScreenModel>()
  val context = LocalContext.current
  val activity = LocalActivity.current!!
  val backStack = LocalBackStack.current
  val density = LocalDensity.current
  val coroutine = rememberCoroutineScope()
  val computers by model.computersPoller.computersFlow.collectAsState(emptyList())
  val loadingPairComputer by model.computersPoller.loadingPairedComputerFlow.collectAsState(false)
  var listContainerHeight by remember { mutableStateOf(0.dp) }

  DisposableEffect(Unit) {
    model.computersPoller.start()
    onDispose { model.computersPoller.stop() }
  }

  StyledPullToRefreshBox(
    isRefreshing = loadingPairComputer || computers.isEmpty(),
    onRefresh = {
      model.computersPoller.restartPollingJob()
    },
  ) {
    Scaffold(
      modifier = Modifier
        .fillMaxSize()
        .imePadding(),
      topBar = {
        StyledTopAppBar(
          title = { TopAppBarTitle(text = stringResource(R.string.app_name)) },
          navigationIcon = {},
          actions = {
            TopAppBarIcon(
              image = Icons.Rounded.Settings,
              onClick = { backStack.add(SettingsEntryKey) }
            )
          }
        )
      }
    ) {
      Box(
        modifier = Modifier
          .padding(it)
          .fillMaxSize(),
      ) {
        Column(
          modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { listContainerHeight = density.run { it.height.toDp() } }
            .verticalScroll(rememberScrollState())
        ) {
          for ((index, item) in computers.withIndex()) {
            AnimatedAppearing(
              enter = fadeIn() + scaleIn(),
              exit = fadeOut() + scaleOut()
            ) {
              val online = item.delayTime != -1
              val paired = (item.delayTime == -1 && item.serverCert != null) || (online && item.pairState == PairingManager.PairState.PAIRED)
              ComputerItem(
                computerName = item.alias ?: item.name,
                online = online,
                serviceOnline = item.connectionState == ComputerDetailsEx.ConnectionState.REACHABLE,
                delayTime = item.delayTime,
                paired = paired,
                loading = item.connectionState == ComputerDetailsEx.ConnectionState.CONNECTING,

                onClick = {
                  if (item.pairState == PairingManager.PairState.PAIRED) {
                    backStack.add(AppListEntryKey(item.uuid, item.alias ?: item.name))
                  } else if (online) {
                    coroutine.launch {
                      if (model.pair(activity, item)) backStack.add(AppListEntryKey(item.uuid, item.alias ?: item.name))
                    }
                  } else {
                    coroutine.launch { model.wakeOnLan(activity, item) }
                  }
                },
                onLongClick = {
                  if (paired) {
                    model.deviceActionsBottomSheetState.show(item.uuid)
                  } else if (online) {
                    coroutine.launch {
                      if (model.pair(activity, item)) backStack.add(AppListEntryKey(item.uuid, item.alias ?: item.name))
                    }
                  } else {
                    toast(context, activity.getString(R.string.computer_offline))
                  }
                }
              )
            }
          }

          if (computers.isEmpty()) {
            EmptyContent(
              height = listContainerHeight,
              icon = Icons.Rounded.Computer,
              message = stringResource(R.string.no_computers)
            )
          }
        }
      }
    }
  }

  DeviceActionsBottomSheet(model.deviceActionsBottomSheetState)
}

@Composable
private fun ComputerItem(
  computerName: String,
  online: Boolean,
  serviceOnline: Boolean,
  delayTime: Int,
  paired: Boolean,
  loading: Boolean,
  onClick: () -> Unit,
  onLongClick: () -> Unit,
) {
  Box(
    modifier = Modifier
      .padding(horizontal = 20.dp)
      .padding(bottom = 10.dp)
      .fillMaxWidth()
      .clip(MaterialTheme.shapes.medium)
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .background(MaterialTheme.colorScheme.primaryContainer)
        .combinedClickable(
          onClick = onClick,
          onLongClick = onLongClick,
        )
        .padding(15.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically
      ) {
        Box(
          modifier = Modifier

        ) {
          Image(
            modifier = Modifier
              .size(45.dp)
              .alpha(if (loading) 0.5f else 1f),
            painter = painterResource(R.drawable.sunshine),
            contentDescription = null,
            colorFilter = if (serviceOnline || loading) null else ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
          )

          if (!paired) {
            Icon(
              modifier = Modifier
                .size(25.dp)
                .align(Alignment.TopEnd),
              imageVector = Icons.Rounded.KeyOff,
              tint = MaterialTheme.colorScheme.primary,
              contentDescription = null,
            )
          }
        }

        Column(
          modifier = Modifier
            .padding(start = 15.dp)
        ) {
          Text(
            text = computerName,
            overflow = TextOverflow.Ellipsis
          )
        }
      }

      Text(
        text = if (online) "${delayTime}ms" else stringResource(R.string.offline),
        color = when {
          !online -> MaterialTheme.colorScheme.onSurfaceVariant
          delayTime < 20 -> Color(0xff28A745)
          delayTime < 40 -> Color(0xFFDC9404)
          else -> Color(0xffDC3545)
        }
      )
    }

    if (loading) {
      LinearProgressIndicator(
        modifier = Modifier
          .fillMaxWidth()
          .align(Alignment.TopCenter),
        trackColor = Color.Transparent
      )
    }
  }
}
