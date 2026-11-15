package koharubiyori.starlight.screen.streaming.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.draggable2D
import androidx.compose.foundation.gestures.rememberDraggable2DState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloseFullscreen
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastRoundToInt
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import koharubiyori.starlight.R
import koharubiyori.starlight.compable.remember.rememberScreenCornerRadii
import koharubiyori.starlight.screen.streaming.StreamingScreenModel
import koharubiyori.starlight.screen.streaming.util.video.MediaCodecDecoderRenderer
import koharubiyori.starlight.store.proto.ReminderStoreData
import koharubiyori.starlight.store.proto.SettingsStoreData
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun PerformanceOverlay(
  performanceInfo: MediaCodecDecoderRenderer.PerformanceInfo,
) {
  val model = hiltViewModel<StreamingScreenModel>()
  val density = LocalDensity.current
  val coroutine = rememberCoroutineScope()
  val screenCornerRadii = rememberScreenCornerRadii()
  val reminderOrNull by model.reminderStore.reminder.collectAsState(null)
  var offset by remember { mutableStateOf(Offset.Zero) }
  var containerSize by remember { mutableStateOf(IntSize.Zero) }
  var overlaySize by remember { mutableStateOf(IntSize.Zero) }

  fun clampOffset(value: Offset) = Offset(
    x = value.x.coerceIn(0f, (containerSize.width - overlaySize.width).coerceAtLeast(0).toFloat()),
    y = value.y.coerceIn(0f, (containerSize.height - overlaySize.height).coerceAtLeast(0).toFloat())
  )

  val reminder = reminderOrNull ?: return

  val topRightCornerRadius = with(density) {
    (screenCornerRadii.getOrNull(1) ?: 0).toDp()
  }

  if (reminder.performanceOverlayCollapsed) {
    Box(
      modifier = Modifier
        .padding(end = topRightCornerRadius)
        .fillMaxSize(),
      contentAlignment = Alignment.TopEnd
    ) {
      Text(
        modifier = Modifier
          .background(Color.Black.copy(0.3f))
          .padding(3.dp)
          .clickable {
            coroutine.launch {
              model.reminderStore.updateReminder { performanceOverlayCollapsed = false }
            }
          },
        text = performanceInfo.renderedFps.roundToInt().toString() + "FPS",
        style = MaterialTheme.typography.bodySmall,
        color = Color.White.copy(0.9f)
      )
    }
  } else {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .onSizeChanged {
          containerSize = it
          offset = clampOffset(offset)
        }
    ) {
      Box(
        modifier = Modifier
          .absoluteOffset { IntOffset(offset.x.fastRoundToInt(), offset.y.fastRoundToInt()) }
          .draggable2D(rememberDraggable2DState { delta -> offset = clampOffset(offset + delta) })
          .onSizeChanged {
            overlaySize = it
            offset = clampOffset(offset)
          }
      ) {
        Column(
          modifier = Modifier
            .clip(MaterialTheme.shapes.medium)
            .background(Color.Black.copy(0.3f))
            .padding(10.dp)
        ) {
          CompositionLocalProvider(
            LocalTextStyle provides MaterialTheme.typography.bodySmall.copy(
              color = Color.White.copy(0.9f),
            )
          ) {
            Text(
              text = stringResource(R.string.f_perf_overlay_streamdetails,
                performanceInfo.width.toString() + "x" + performanceInfo.height, performanceInfo.totalFps)
            )
            Text(
              text = stringResource(R.string.f_perf_overlay_decoder, performanceInfo.decoder)
            )
            Text(
              text = stringResource(R.string.f_perf_overlay_incomingfps, performanceInfo.receivedFps)
            )
            Text(
              text = stringResource(R.string.f_perf_overlay_renderingfps, performanceInfo.renderedFps)
            )
            Text(
              text = stringResource(R.string.f_perf_overlay_netdrops, performanceInfo.networkFrameDropPercentage)
            )
            Text(
              text = stringResource(R.string.f_perf_overlay_netlatency, performanceInfo.networkLatencyMs, performanceInfo.networkLatencyVarianceMs)
            )
            if (performanceInfo.hostProcessingLatencies != null) {
              Text(
                text = stringResource(
                  R.string.f_perf_overlay_hostprocessinglatency,
                  performanceInfo.hostProcessingLatencies.min,
                  performanceInfo.hostProcessingLatencies.max,
                  performanceInfo.hostProcessingLatencies.average
                )
              )
            }
            Text(
              text = stringResource(R.string.f_perf_overlay_dectime, performanceInfo.averageDecodingTimeMs)
            )
          }
        }

        Icon(
          modifier = Modifier
            .size(18.dp)
            .align(Alignment.TopEnd)
            .absoluteOffset(x = (-10).dp, y = 10.dp)
            .clickable {
              coroutine.launch {
                model.reminderStore.updateReminder { performanceOverlayCollapsed = true }
              }
            },
          imageVector = Icons.Rounded.CloseFullscreen,
          contentDescription = null,
          tint = Color.White
        )
      }
    }
  }
}