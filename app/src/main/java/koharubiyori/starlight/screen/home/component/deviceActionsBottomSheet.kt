package koharubiyori.starlight.screen.home.component

import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AppShortcut
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.node.Ref
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import koharubiyori.starlight.Globals
import koharubiyori.starlight.R
import koharubiyori.starlight.component.BottomSheetContainer
import koharubiyori.starlight.component.VerifiableTextField
import koharubiyori.starlight.component.VerifiableTextFieldRef
import koharubiyori.starlight.component.commonDialog.ButtonConfig
import koharubiyori.starlight.component.commonDialog.CommonAlertDialogProps
import koharubiyori.starlight.nav.LocalBackStack
import koharubiyori.starlight.screen.appList.AppListEntryKey
import koharubiyori.starlight.screen.home.HomeScreenModel
import koharubiyori.starlight.util.PlaceholderTransformation
import koharubiyori.starlight.util.vibrate
import kotlinx.coroutines.launch

@Composable
fun DeviceActionsBottomSheet(
  state: DeviceActionsBottomSheetState
) {
  BottomSheetContainer(
    visible = state.visible,
    onClickMask = { state.hide() }
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .clip(MaterialTheme.shapes.large.copy(bottomStart = ZeroCornerSize, bottomEnd = ZeroCornerSize))
        .background(MaterialTheme.colorScheme.surface)
        .padding(top = 15.dp)
        .padding(horizontal = 15.dp)
    ) {
      ComposedHeader(state)
      ComposedActionButtons(state)
    }
  }
}

class DeviceActionsBottomSheetState {
  var visible by mutableStateOf(false)
  var computerUuid by mutableStateOf<String?>(null)
    private set

  fun show(computerUuid: String) {
    this.computerUuid = computerUuid
    visible = true
  }

  fun hide() {
    visible = false
  }
}

@Composable
private fun ComposedButton(
  text: String,
  icon: ImageVector,
  loading: Boolean = false,
  enabled: Boolean = true,
  onClick: () -> Unit
) {
  val context = LocalContext.current
  val animatedIconSize by animateDpAsState(if (loading) 15.dp else 30.dp)

  Button(
    modifier = Modifier
      .padding(bottom = 15.dp)
      .fillMaxWidth()
      .height(50.dp)
      .alpha(if (loading) 0.5f else 1f),
    enabled = enabled,
    onClick = {
      if (loading) return@Button
      vibrate(context)
      onClick()
    }
  ) {
    Box(
      contentAlignment = Alignment.Center
    ) {
      Icon(
        modifier = Modifier
          .size(animatedIconSize),
        imageVector = icon,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onPrimary
      )

      this@Button.AnimatedVisibility(
        visible = loading,
        enter = fadeIn(),
        exit = fadeOut()
      ) {
        CircularWavyProgressIndicator(
          modifier = Modifier
            .size(30.dp),
          color = MaterialTheme.colorScheme.primaryFixed,
          trackColor = MaterialTheme.colorScheme.onPrimary,
          amplitude = 0f,
          wavelength = 2.dp,
          waveSpeed = 4.dp,
        )
      }
    }

    Text(
      modifier = Modifier
        .padding(start = 10.dp),
      text = text,
      textAlign = TextAlign.Center,
      color = MaterialTheme.colorScheme.onPrimary,
      style = MaterialTheme.typography.titleMedium
    )
  }
}

@Composable
private fun ComposedHeader(
  state: DeviceActionsBottomSheetState,
) {
  val model = hiltViewModel<HomeScreenModel>()
  val context = LocalContext.current
  val activity = LocalActivity.current!!
  val coroutine = rememberCoroutineScope()
  val computerDetailsOrNull by model.getComputerFlowByUuid(state.computerUuid!!).collectAsState(null)

  fun showUnpairChecker() {
    val computerDetails = requireNotNull(computerDetailsOrNull)
    Globals.commonAlertDialog.show(CommonAlertDialogProps(
      content = {
        Text(stringResource(R.string.f_check_to_unpair_computer, computerDetails.alias ?: computerDetails.name))
      },
      secondaryButton = ButtonConfig.cancelButton(activity.getString(R.string.cancel)),
      onPrimaryButtonClick = {
        coroutine.launch {
          model.unpair(activity, computerDetails)
          state.hide()
        }
      }
    ))
  }

  fun showRenameDialog() {
    val computerDetails = requireNotNull(computerDetailsOrNull)
    var value by mutableStateOf(computerDetails.alias ?: "")
    val textFieldRef = Ref<VerifiableTextFieldRef>()
    Globals.commonAlertDialog.show(CommonAlertDialogProps(
      title = activity.getString(R.string.rename),
      hideOnAction = false,
      content = {
        VerifiableTextField(
          value = value,
          colors = PlaceholderTransformation.textFieldColors(
            inputValueEmpty = value.isEmpty(),
            errorTextColor = MaterialTheme.colorScheme.error
          ),
          onValueChange = { value = it },
          visualTransformation = if (value.isEmpty()) PlaceholderTransformation(computerDetails.name) else VisualTransformation.None,
          ref = textFieldRef
        )
      },
      onPrimaryButtonClick = {
        coroutine.launch {
          if (!textFieldRef.value!!.validate()) return@launch
          model.pairedComputerStore.renameComputer(state.computerUuid!!, value.trim().ifEmpty { null })
          Globals.commonAlertDialog.hide()
        }
      },
    ))
  }

  if (computerDetailsOrNull == null) return
  Row(
    modifier = Modifier
      .fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
  ) {
    // Unpair button
    OutlinedButton(
      modifier = Modifier
        .width(140.dp),
      colors = ButtonDefaults.outlinedButtonColors(
        contentColor = MaterialTheme.colorScheme.error,
      ),
      border = BorderStroke(
        width = 1.dp,
        color = MaterialTheme.colorScheme.error
      ),
      contentPadding = PaddingValues.Zero,
      onClick = { showUnpairChecker() }
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically
      ) {
        Icon(
          modifier = Modifier
            .size(25.dp),
          imageVector = Icons.Rounded.LinkOff,
          contentDescription = null,
        )

        Text(
          modifier = Modifier
            .padding(start = 10.dp),
          text = stringResource(R.string.unpair),
          style = MaterialTheme.typography.titleSmall
        )
      }
    }

    Spacer(modifier = Modifier
      .weight(1f))

    // Rename Button
    OutlinedButton(
      modifier = Modifier
        .width(140.dp),
      colors = ButtonDefaults.outlinedButtonColors(
        contentColor = MaterialTheme.colorScheme.primary,
      ),
      border = BorderStroke(
        width = 1.dp,
        color = MaterialTheme.colorScheme.primary
      ),
      contentPadding = PaddingValues.Zero,
      onClick = { showRenameDialog() }
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically
      ) {
        Icon(
          modifier = Modifier
            .size(25.dp),
          imageVector = Icons.Rounded.Edit,
          contentDescription = null,
        )

        Text(
          modifier = Modifier
            .padding(start = 10.dp),
          text = stringResource(R.string.rename),
          style = MaterialTheme.typography.titleSmall
        )
      }
    }
  }
}

@Composable
private fun ComposedActionButtons(
  state: DeviceActionsBottomSheetState,
) {
  val model = hiltViewModel<HomeScreenModel>()
  val backStack = LocalBackStack.current
  val activity = LocalActivity.current!!
  val coroutine = rememberCoroutineScope()
  val computerDetailsOrNull by model.getComputerFlowByUuid(state.computerUuid!!).collectAsState(null)

  if (computerDetailsOrNull == null) return
  val computerDetails = computerDetailsOrNull!!
  Column(
    modifier = Modifier
      .padding(top = 15.dp)
  ) {
    ComposedButton(
      text = stringResource(R.string.app_list),
      icon = Icons.Rounded.Apps,
      onClick = { backStack.add(AppListEntryKey(computerDetails.uuid, computerDetails.alias ?: computerDetails.name)) }
    )
    ComposedButton(
      text = stringResource(R.string.wake),
      icon = Icons.Rounded.Bolt,
      onClick = { coroutine.launch { model.wakeOnLan(activity, computerDetails) } }
    )
    ComposedButton(
      text = stringResource(R.string.create_shortcut),
      icon = Icons.Rounded.AppShortcut,
      onClick = { coroutine.launch { model.createShortcut(activity, computerDetails) } }
    )
  }
}

@Composable
private fun <T> CrossFlipHorizontal(
  targetState: T,
  modifier: Modifier = Modifier,
  content: @Composable (T) -> Unit
) {
  val density = LocalDensity.current.density
  var current by remember { mutableStateOf(targetState) }
  val rotation = remember { Animatable(0f) }
  val isBack = remember { mutableStateOf(false) }

  LaunchedEffect(targetState) {
    if (targetState != current) {
      isBack.value = true
      // Flip to 90°
      rotation.animateTo(
        90f,
        animationSpec = tween(250, easing = FastOutLinearInEasing)
      )
      // Change the content
      current = targetState
      isBack.value = false
      // Flip the remaining 90°
      rotation.animateTo(
        0f,
        animationSpec = tween(250, easing = LinearOutSlowInEasing)
      )
    }
  }

  val displayedRotation = if (isBack.value) rotation.value else -rotation.value

  Box(
    modifier = modifier.graphicsLayer {
      rotationY = displayedRotation
      cameraDistance = 8 * density
    }
  ) {
    content(current)
  }
}
