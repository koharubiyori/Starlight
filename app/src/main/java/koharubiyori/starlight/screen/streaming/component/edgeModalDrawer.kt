package koharubiyori.starlight.screen.streaming.component

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.node.Ref
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import koharubiyori.starlight.R
import koharubiyori.starlight.compable.remember.rememberDisplayCutoutInsets
import koharubiyori.starlight.util.debugPrint
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun EdgeModalDrawer(
  drawerWidth: Dp,
  modifier: Modifier = Modifier,
  initiallyExpanded: Boolean = false,
  drawerContainerColor: Color = Color(0xFF2D2D2D),
  scrimColor: Color = Color.Black,
  scrimMaxAlpha: Float = 0.32f,
  ref: Ref<EdgeModalDrawerRef>? = null,
  showSwipeHint: Boolean = false,
  onSwipeHintFinished: () -> Unit = {},
  content: @Composable () -> Unit
) {
  val density = LocalDensity.current
  val viewConfiguration = LocalViewConfiguration.current
  val layoutDirection = LocalLayoutDirection.current
  val coroutineScope = rememberCoroutineScope()
  val drawerWidthPx = with(density) { drawerWidth.toPx() }
  val edgeWidthPx = with(density) { 24.dp.toPx() }
  val touchSlop = viewConfiguration.touchSlop
  val hintPullOffset = remember { Animatable(0f) }
  val hintPullOffsetPx = with(density) { hintPullOffset.value.dp.toPx() }
  var drawerOffset by remember(drawerWidthPx) {
    mutableFloatStateOf(if (initiallyExpanded) 0f else -drawerWidthPx)
  }
  val currentDrawerOffset = rememberUpdatedState(drawerOffset)
  var animationJob by remember { mutableStateOf<Job?>(null) }
  val expanded = drawerOffset > -drawerWidthPx
  val drawerProgress = ((drawerOffset + drawerWidthPx) / drawerWidthPx).coerceIn(0f, 1f)

  fun showDrawer() {
    animationJob?.cancel()
    animationJob = coroutineScope.launch {
      animate(drawerOffset, 0f) { value, _ -> drawerOffset = value }
    }
  }

  fun closeDrawer() {
    animationJob?.cancel()
    animationJob = coroutineScope.launch {
      animate(drawerOffset, -drawerWidthPx) { value, _ -> drawerOffset = value }
    }
  }

  SideEffect {
    ref?.value = EdgeModalDrawerRef(
      open = ::showDrawer,
      close = ::closeDrawer
    )
  }

  BackHandler(enabled = expanded, onBack = ::closeDrawer)

  Box(modifier = modifier.fillMaxSize()) {
    // Keep this pointerInput node composed for the whole gesture so opening the drawer
    // does not cancel the pointer stream before the release event.
    Box(
      modifier = Modifier
        .align(Alignment.CenterStart)
        .width(24.dp)
        .fillMaxHeight()
        .pointerInput(drawerWidthPx, edgeWidthPx, touchSlop) {
          awaitEachGesture {
            val down = awaitFirstDown(
              requireUnconsumed = false,
              pass = PointerEventPass.Initial
            )
            if (down.position.x > edgeWidthPx) return@awaitEachGesture

            val startX = down.position.x
            val startY = down.position.y
            var lastX = startX
            var dragging = false
            while (true) {
              val event = awaitPointerEvent(PointerEventPass.Initial)
              val change = event.changes.firstOrNull { it.id == down.id } ?: break
              if (!change.pressed) break

              val totalX = change.position.x - startX
              val totalY = change.position.y - startY
              if (!dragging) {
                if (abs(totalY) > touchSlop && abs(totalY) >= abs(totalX)) break
                if (
                  abs(totalX) > touchSlop &&
                  abs(totalX) > abs(totalY) &&
                  (drawerOffset > -drawerWidthPx || totalX > 0f)
                ) {
                  dragging = true
                  animationJob?.cancel()
                  change.consume()
                  drawerOffset = (drawerOffset + totalX).coerceIn(-drawerWidthPx, 0f)
                }
              } else {
                change.consume()
                val deltaX = change.position.x - lastX
                drawerOffset = (drawerOffset + deltaX).coerceIn(-drawerWidthPx, 0f)
              }
              lastX = change.position.x
            }

            if (dragging) {
              val target = if (drawerOffset > -drawerWidthPx * 0.667) 0f else -drawerWidthPx
              animationJob = coroutineScope.launch {
                animate(drawerOffset, target) { value, _ -> drawerOffset = value }
              }
            }
          }
        }
    )

    if (expanded) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .background(scrimColor.copy(alpha = scrimMaxAlpha * drawerProgress))
          .clickable(onClick = ::closeDrawer)
      )
    }

    ModalDrawerSheet(
      drawerContainerColor = drawerContainerColor,
      windowInsets = WindowInsets(0, 0),
      modifier = Modifier
        .offset { IntOffset((drawerOffset + hintPullOffsetPx).roundToInt(), 0) }
        .width(drawerWidth)
        .pointerInput(drawerWidthPx, touchSlop) {
          awaitEachGesture {
            val down = awaitFirstDown(
              requireUnconsumed = false,
              pass = PointerEventPass.Initial
            )
            if (currentDrawerOffset.value <= -drawerWidthPx) return@awaitEachGesture
            val startX = down.position.x
            val startY = down.position.y
            var dragging = false
            var totalDragX = 0f
            while (true) {
              val event = awaitPointerEvent(PointerEventPass.Initial)
              val change = event.changes.firstOrNull { it.id == down.id } ?: break
              if (!change.pressed) break

              val totalX = change.position.x - startX
              val totalY = change.position.y - startY
              if (!dragging) {
                if (abs(totalY) > touchSlop && abs(totalY) >= abs(totalX)) break
                if (totalX < -touchSlop && abs(totalX) > abs(totalY)) {
                  dragging = true
                  animationJob?.cancel()
                  change.consume()
                  totalDragX = totalX
                }
              } else {
                change.consume()
                totalDragX = totalX
              }
            }

            if (dragging && totalDragX <= -drawerWidthPx / 3f) {
              closeDrawer()
            }
          }
        }
    ) {
      content()
    }

    if (showSwipeHint) {
      EdgeDrawerSwipeHint(
        horizontalOffset = hintPullOffset,
        hasLeftNotch = WindowInsets.displayCutout.getLeft(density, layoutDirection) > 0,
        onAnimationFinished = onSwipeHintFinished
      )
    }
  }
}

@Composable
private fun BoxScope.EdgeDrawerSwipeHint(
  horizontalOffset: Animatable<Float, AnimationVector1D>,
  hasLeftNotch: Boolean,
  onAnimationFinished: () -> Unit
) {
  LaunchedEffect(Unit) {
    repeat(3) {
      horizontalOffset.animateTo(20f, tween(durationMillis = 500))
      horizontalOffset.animateTo(0f, tween(durationMillis = 500))
    }
    onAnimationFinished()
  }

  Box(
    modifier = Modifier
      .align(Alignment.CenterStart)
      .offset(y = if (hasLeftNotch) (-50).dp else 0.dp)
      .offset(x = horizontalOffset.value.dp)
      .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(24.dp))
      .padding(horizontal = 12.dp, vertical = 10.dp)
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Icon(
        imageVector = Icons.Rounded.TouchApp,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier.size(28.dp)
      )
      Text(
        text = stringResource(R.string.s_drawer_swipe_hint),
        color = MaterialTheme.colorScheme.onPrimary,
        style = MaterialTheme.typography.labelMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 8.dp)
      )
    }
  }
}

class EdgeModalDrawerRef(
  val open: () -> Unit,
  val close: () -> Unit
)