package koharubiyori.starlight.nav

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import koharubiyori.starlight.R
import koharubiyori.starlight.compable.ActivityEventHandler
import koharubiyori.starlight.compable.OneTimeLaunchedEffect
import koharubiyori.starlight.screen.appList.AppListEntryKey
import koharubiyori.starlight.screen.appList.AppListScreen
import koharubiyori.starlight.screen.home.HomeEntryKey
import koharubiyori.starlight.screen.home.HomeScreen
import koharubiyori.starlight.screen.settings.SettingsEntryKey
import koharubiyori.starlight.screen.settings.SettingsScreen
import koharubiyori.starlight.screen.streaming.StreamingEntryKey
import koharubiyori.starlight.screen.streaming.StreamingScreen
import koharubiyori.starlight.util.InitRef
import koharubiyori.starlight.util.getValue
import koharubiyori.starlight.util.setValue
import koharubiyori.starlight.util.toast
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

interface NavEntryKey
interface DeepLinkedNavEntryKey : NavEntryKey {
  fun toIntent(context: Context): Intent

  companion object {
    const val SCREEN_NAME = "screenName"
    const val APP_LIST = "appList"
    const val STREAMING = "streaming"
  }
}


private val singleBackStack = mutableStateListOf<NavEntryKey>(HomeEntryKey)
//private val singleBackStack = mutableStateListOf<NavEntryKey>(StreamingEntryKey(
//  "FEB62E60-0725-712A-868F-857D224EA4A1",
//  881448767,
//  "Desktop"
//))
val LocalBackStack = staticCompositionLocalOf { singleBackStack }

@Composable
fun Routes(
  intent: Intent? = null,
) {
  DoAtOnceEffect {
    intent?.toNavEntryKey()?.let { singleBackStack.add(it) }
  }

  ActivityEventHandler(
    onNewIntent = { it.toNavEntryKey()?.let { singleBackStack.add(it) } }
  )

  PressBackAgainToExit()

  AnimatedNavDisplay(
    backStack = singleBackStack,
    entryDecorators = listOf(
      rememberSaveableStateHolderNavEntryDecorator(),
      rememberViewModelStoreNavEntryDecorator()
    ),
    onBack = { singleBackStack.removeLastOrNull() },
    animatedNavItems = listOf(
      AnimatedNavItem(HomeEntryKey::class) { HomeScreen() },
      AnimatedNavItem(SettingsEntryKey::class) { SettingsScreen() },
      AnimatedNavItem(AppListEntryKey::class) { AppListScreen(it) },
      AnimatedNavItem(StreamingEntryKey::class, NavAnimation.NONE) { StreamingScreen(it) }
    )
  )
}

private fun Intent.toNavEntryKey(): NavEntryKey? {
  val screenName = getStringExtra(DeepLinkedNavEntryKey.SCREEN_NAME)
  return when (screenName) {
    DeepLinkedNavEntryKey.APP_LIST -> AppListEntryKey.fromIntent(this)
    DeepLinkedNavEntryKey.STREAMING -> StreamingEntryKey.fromIntent(this)
    else -> null
  }
}

@Composable
private fun PressBackAgainToExit() {
  val context = LocalContext.current
  val activity = LocalActivity.current!!
  val coroutine = rememberCoroutineScope()
  val backPressed = remember { InitRef(false) }

  BackHandler {
    if (singleBackStack.size > 1) return@BackHandler
    if (backPressed.value) {
      activity.finishAndRemoveTask()
    } else {
      backPressed.value = true
      coroutine.launch {
        delay(2.seconds)
        backPressed.value = false
      }
      toast(context, activity.getString(R.string.s_press_back_again_to_exit))
    }
  }
}

@Composable
private fun DoAtOnceEffect(block: () -> Unit) {
  val did = rememberSaveable(saver = InitRef.saver()) { InitRef(false) }
  if (!did.value) {
    block()
    did.value = true
  }
}