package koharubiyori.starlight.nav

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandIn
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneDecoratorStrategy
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SinglePaneSceneStrategy
import androidx.navigation3.ui.NavDisplay
import koharubiyori.starlight.screen.appList.AppListEntryKey
import koharubiyori.starlight.util.debugPrint
import timber.log.Timber
import kotlin.collections.removeLastOrNull
import kotlin.reflect.KClass

@Composable
fun <T : NavEntryKey> AnimatedNavDisplay(
  backStack: List<T>,
  modifier: Modifier = Modifier,
  contentAlignment: Alignment = Alignment.TopStart,
  onBack: () -> Unit = {
    if (backStack is MutableList<T>) {
      backStack.removeLastOrNull()
    }
  },
  entryDecorators: List<NavEntryDecorator<T>> =
    listOf(rememberSaveableStateHolderNavEntryDecorator()),
  sceneStrategies: List<SceneStrategy<T>> = listOf(SinglePaneSceneStrategy()),
  sceneDecoratorStrategies: List<SceneDecoratorStrategy<T>> = emptyList(),
  sharedTransitionScope: SharedTransitionScope? = null,
  animatedNavItems: List<AnimatedNavItem<out T>> = emptyList()
) {
  NavDisplay(
    backStack = backStack,
    modifier = modifier,
    contentAlignment = contentAlignment,
    onBack = onBack,
    entryDecorators = entryDecorators,
    sceneStrategies = sceneStrategies,
    sceneDecoratorStrategies = sceneDecoratorStrategies,
    sharedTransitionScope = sharedTransitionScope,
    transitionSpec = { animationFor(targetState.metadata.animation).transition },
    popTransitionSpec = { animationFor(initialState.metadata.animation).popTransition },
    predictivePopTransitionSpec = { animationFor(initialState.metadata.animation).popTransition },
    entryProvider = { key ->
      val hitEntry = animatedNavItems.firstOrNull { it.key == key::class }
      if (hitEntry != null) {
        if (key is DeepLinkedNavEntryKey) Timber.i("Deep linked entry: $key")

        NavEntry(
          key = key,
          metadata = hitEntry.metadata + mapOf(ANIMATION_KEY to hitEntry.animation)
        ) { hitEntry.ContentFor(key) }
      } else {
        NavEntry(key) { Text("Unknown route") }
      }
    }
  )
}

enum class NavAnimation {
  TRAINSLATE,
  APPEAR,
  NONE,
}

class AnimatedNavItem<T : NavEntryKey>(
  val key: KClass<T>,
  val animation: NavAnimation = NavAnimation.TRAINSLATE,
  val metadata: Map<String, Any> = emptyMap(),
  private val content: @Composable (key: T) -> Unit
) {
  @Composable
  internal fun ContentFor(key: NavEntryKey) {
    content(requireNotNull(this.key.java.cast(key)))
  }
}

private const val ANIMATION_KEY = "__ANIMATED_NAV_DISPLAY_ANIMATION_KEY__"

private class AnimationGroup(
  val transition: ContentTransform,
  val popTransition: ContentTransform
)

private val Map<String, Any>.animation get() = this[ANIMATION_KEY] as? NavAnimation ?: NavAnimation.TRAINSLATE

private fun animationFor(animation: NavAnimation) = when (animation) {
  NavAnimation.TRAINSLATE -> {
    val animationSpec = tween<IntOffset>(400, easing = FastOutSlowInEasing)
    AnimationGroup(
      transition = slideInHorizontally(
        initialOffsetX = { it },
        animationSpec = animationSpec
      ) togetherWith slideOutHorizontally(
        targetOffsetX = { -it },
        animationSpec = animationSpec
      ),
      popTransition = slideInHorizontally(
        initialOffsetX = { -it },
        animationSpec = animationSpec
      ) togetherWith slideOutHorizontally(
        targetOffsetX = { it },
        animationSpec = animationSpec
      )
    )
  }

  NavAnimation.APPEAR -> {
    val animationFloatSpec = tween<Float>(4000, easing = FastOutSlowInEasing)
    AnimationGroup(
      transition =
        scaleIn(
          initialScale = 0.6f,
          animationSpec = animationFloatSpec
        ) + fadeIn(animationFloatSpec)
            togetherWith ExitTransition.None,

      popTransition = EnterTransition.None togetherWith
          scaleOut(
            targetScale = 0.6f,
            animationSpec = animationFloatSpec
          ) + fadeOut(animationFloatSpec),
    )
  }

  NavAnimation.NONE -> AnimationGroup(
    transition = EnterTransition.None togetherWith ExitTransition.None,
    popTransition = EnterTransition.None togetherWith ExitTransition.None
  )
}