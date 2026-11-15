package koharubiyori.starlight.screen.appList

import android.content.pm.ActivityInfo
import android.content.pm.ShortcutManager
import android.content.res.Configuration
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AppShortcut
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.PlayCircleFilled
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import koharubiyori.starlight.Globals
import koharubiyori.starlight.R
import koharubiyori.starlight.component.EmptyContent
import koharubiyori.starlight.component.animation.AnimatedAppearing
import koharubiyori.starlight.component.commonDialog.ButtonConfig
import koharubiyori.starlight.component.commonDialog.CommonAlertDialogProps
import koharubiyori.starlight.component.styled.StyledPullToRefreshBox
import koharubiyori.starlight.component.styled.StyledTopAppBar
import koharubiyori.starlight.component.styled.TopAppBarTitle
import koharubiyori.starlight.nav.LocalBackStack
import koharubiyori.starlight.nvstream.http.NvApp
import koharubiyori.starlight.screen.appList.util.isPlaceholderImage
import koharubiyori.starlight.screen.streaming.StreamingEntryKey
import koharubiyori.starlight.util.LoadStatus
import koharubiyori.starlight.util.debugPrint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Composable
fun AppListScreen(entryKey: AppListEntryKey) {
  val model = hiltViewModel<AppListScreenModel, AppListScreenModel.Factory>(
    creationCallback = { factory -> factory.create(entryKey) }
  )
  val backStack = LocalBackStack.current
  val activity = LocalActivity.current!!
  val context = LocalContext.current
  val density = LocalDensity.current
  val configuration = LocalConfiguration.current
  val coroutine = rememberCoroutineScope()
  var pinShortcutSupported by remember { mutableStateOf(false) }
  var listContainerHeight by remember { mutableStateOf(0.dp) }

  LaunchedEffect(Unit) {
    model.loadAppList(activity)
    val shortcutManager = context.getSystemService(ShortcutManager::class.java)
    pinShortcutSupported = shortcutManager.isRequestPinShortcutSupported
  }

  LaunchedEffect(Unit) {
    while (true) {
      model.updateComputerDetails()
      delay(1.seconds)
    }
  }

  fun gotoStreamingScreen(app: NvApp) {
    if (model.latestComputerDetails!!.runningGameId.let { it != 0 && it != app.appId }) {
      val currentAppName = model.appList.firstOrNull { it.appId == model.latestComputerDetails!!.runningGameId }?.appName ?: ""
      Globals.commonAlertDialog.show(CommonAlertDialogProps(
        content = {
          Text(stringResource(R.string.f_stop_current_app_and_start, currentAppName, app.appName))
        },
        onPrimaryButtonClick = {
          backStack.add(StreamingEntryKey(model.latestComputerDetails!!.uuid, app.appId, app.appName))
        },
        secondaryButton = ButtonConfig.cancelButton(activity.getString(R.string.cancel))
      ))
    } else {
      backStack.add(StreamingEntryKey(model.latestComputerDetails!!.uuid, app.appId, app.appName))
    }
  }

  StyledPullToRefreshBox(
    isRefreshing = model.loadStatus == LoadStatus.LOADING,
    onRefresh = {
      coroutine.launch { model.loadAppList(activity) }
    },
  ) {
    Scaffold(
      modifier = Modifier
        .fillMaxSize()
        .imePadding(),
      topBar = {
        StyledTopAppBar(
          title = { TopAppBarTitle(text = entryKey.computerName) },
          actions = {

          }
        )
      }
    ) {
      debugPrint(it.calculateTopPadding(), it.calculateRightPadding(LayoutDirection.Ltr))
      Box(
        modifier = Modifier
          .padding(it)
          .fillMaxSize(),
      ) {
        val column = 3
        FlowRow(
          modifier = Modifier
            .padding(horizontal = 5.dp)
            .fillMaxSize()
            .onSizeChanged { listContainerHeight = density.run { it.height.toDp() } }
            .verticalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.SpaceEvenly,
          maxItemsInEachRow = column
        ) {
          for ((index, item) in model.appList.withIndex()) {
            AnimatedAppearing(
              modifier = Modifier.weight(1f),
              enter = fadeIn() + scaleIn(),
              exit = fadeOut() + scaleOut(),
              delayAppear = (index * 100).milliseconds
            ) {
              var appMenuExpended by remember { mutableStateOf(false) }
              var appMenuPosition by remember { mutableStateOf(IntOffset.Zero) }

              Box(Modifier.fillMaxSize()) {
                AppItem(
                  modifier = Modifier.fillMaxSize(),
                  name = item.appName,
                  image = model.coverImgMap[item.appId]?.let { if (it.isPlaceholderImage()) null else it.asImageBitmap() },
                  running = model.latestComputerDetails!!.runningGameId == item.appId,
                  onClick = { gotoStreamingScreen(item) },
                  onLongClick = { position ->
                    appMenuPosition = IntOffset(position.x.toInt(), position.y.toInt())
                    appMenuExpended = true
                  }
                )
                Box(
                  modifier = Modifier
                    .offset { appMenuPosition }
                    .size(0.dp)
                ) {
                  DropdownMenu(
                    expanded = appMenuExpended,
                    onDismissRequest = { appMenuExpended = false },
                    properties = PopupProperties(
                      dismissOnBackPress = true,
                      dismissOnClickOutside = true
                    )
                  ) {
                    if (model.latestComputerDetails!!.runningGameId == item.appId) {
                      DropdownMenuItem(
                        leadingIcon = {
                          Icon(
                            imageVector = Icons.Rounded.StopCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                          )
                        },
                        text = { Text(stringResource(R.string.stop_app)) },
                        onClick = {
                          coroutine.launch { model.stopApp(activity, item.appName) }
                          appMenuExpended = false
                        }
                      )
                    }
//                    DropdownMenuItem(
//                      leadingIcon = {
//                        Icon(
//                          imageVector = Icons.Rounded.VisibilityOff,
//                          contentDescription = null,
//                          tint = MaterialTheme.colorScheme.primary
//                        )
//                      },
//                      text = { Text(stringResource(R.string.hide_app)) },
//                      onClick = {}
//                    )
                    if (pinShortcutSupported) {
                      DropdownMenuItem(
                        leadingIcon = {
                          Icon(
                            imageVector = Icons.Rounded.AppShortcut,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                          )
                        },
                        text = { Text(stringResource(R.string.create_shortcut)) },
                        onClick = {
                          coroutine.launch { model.createShortcut(activity,item) };
                          appMenuExpended = false
                        }
                      )
                    }
                  }
                }
              }
            }
          }

          val rest = model.appList.size % column
          repeat(if (rest != 0) column - rest else 0) { Spacer(Modifier.weight(1f)) }

          if (model.appList.isEmpty()) {
            EmptyContent(
              height = listContainerHeight,
              icon = Icons.Rounded.Apps,
              message = stringResource(R.string.no_apps)
            )
          }
        }
      }
    }
  }
}

@Composable
private fun AppItem(
  modifier: Modifier = Modifier,
  name: String,
  image: ImageBitmap?,
  running: Boolean,
  onClick: () -> Unit,
  onLongClick: (Offset) -> Unit
) {
  val density = LocalDensity.current
  var actualContainerWidthDp by remember { mutableFloatStateOf(0f) }
  var pointerPosition by remember { mutableStateOf(Offset.Zero) }

  Box(
    modifier = Modifier
      .then(modifier)
      .pointerInput(Unit) {
        awaitEachGesture {
          val down = awaitFirstDown(
            requireUnconsumed = false,
            pass = PointerEventPass.Initial
          )
          pointerPosition = down.position
        }
      }
      .padding(bottom = 10.dp)
      .padding(horizontal = 5.dp)
      .clip(MaterialTheme.shapes.small)
      .onSizeChanged {
        actualContainerWidthDp = density.run { it.width.toDp().value }
      }
      .combinedClickable(
        onClick = onClick,
        onLongClick = { onLongClick(pointerPosition) }
      )
  ) {
    Column {
      Box(
        modifier = Modifier
          // This appears to be a bug: if we don't specify an exact width, it will stretch the container after the image finishes loading.
          .width(actualContainerWidthDp.dp)
          .aspectRatio(1 / 1.4f)
          .clip(MaterialTheme.shapes.small),
        contentAlignment = Alignment.Center
      ) {
        if (image != null) {
          Image(
            modifier = Modifier
              .fillMaxSize(),
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.Crop
          )
        } else {
          Box(
            modifier = Modifier
              .fillMaxSize()
              .background(MaterialTheme.colorScheme.primaryContainer)
              .clip(MaterialTheme.shapes.small),
            contentAlignment = Alignment.Center
          ) {
            Icon(
              modifier = Modifier
                .size(40.dp),
              imageVector = Icons.Rounded.SportsEsports,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
          }
        }

        if (running) {
          Box(
            modifier = Modifier
              .fillMaxSize()
              .background(Color.Black.copy(0.2f)),
            contentAlignment = Alignment.Center
          ) {
            Icon(
              modifier = Modifier
                .size(60.dp),
              imageVector = Icons.Rounded.PlayCircleFilled,
              contentDescription = null,
              tint = Color.White.copy(0.8f)
            )
          }
        }
      }

      Text(
        modifier = Modifier
          .padding(top = 5.dp)
          .fillMaxWidth(),
        text = name,
        style = MaterialTheme.typography.titleSmall,
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
      )
    }
  }
}