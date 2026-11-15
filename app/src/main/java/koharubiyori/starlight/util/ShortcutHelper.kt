package koharubiyori.starlight.util

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import koharubiyori.starlight.R
import koharubiyori.starlight.screen.appList.AppListEntryKey
import koharubiyori.starlight.screen.streaming.StreamingEntryKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

object ShortcutHelper {
  private const val PENDING_INTENT_ID = "PENDING_INTENT_ID"
  private var incrementId = 0
  private val pinningShortcuts =
    mutableMapOf<String, CompletableDeferred<Unit>>()  // shortcutId, appName

  suspend fun requestPinAppShortcut(
    context: Context,
    shortcutId: String,
    computerUuid: String,
    appId: Int,
    appName: String,
    icon: Bitmap?
  ): Boolean {
    val shortcutIcon = Icon.createWithAdaptiveBitmap(
      icon ?: context.drawableToBitmap(
        R.drawable.sports_esports_24px,
        0xFF5C6BC0.toInt(),
        0xFFFFFFFF.toInt()
      )
    )

    val shortcutManager = context.getSystemService(ShortcutManager::class.java)

    return requestPinShortcut(
      context,
      shortcutManager,
      shortcutId,
      appName,
      shortcutIcon,
      StreamingEntryKey(computerUuid, appId, appName).toIntent(context)
    )
  }

  suspend fun requestPinComputerShortcut(
    context: Context,
    shortcutId: String,
    computerUuid: String,
    computerName: String
  ): Boolean {
    val shortcutIcon = Icon.createWithResource(context, R.drawable.sunshine)
    val shortcutManager = context.getSystemService(ShortcutManager::class.java)
    return requestPinShortcut(
      context,
      shortcutManager,
      shortcutId,
      computerName,
      shortcutIcon,
      AppListEntryKey(computerUuid, computerName).toIntent(context)
    )
  }

  private suspend fun requestPinShortcut(
    context: Context,
    shortcutManager: ShortcutManager,
    shortcutId: String,
    label: String,
    icon: Icon,
    intent: Intent
  ): Boolean {
    val shortcut = ShortcutInfo.Builder(context, shortcutId)
      .setShortLabel(label)
      .setIcon(icon)
      .setIntent(intent)
      .build()

    val callbackIntent = PendingIntent.getBroadcast(
      context,
      incrementId++,
      Intent(context, ShortcutPinnedReceiver::class.java).apply {
        putExtra(PENDING_INTENT_ID, shortcutId)
      },
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val deferred = CompletableDeferred<Unit>()
    pinningShortcuts[shortcutId] = deferred
    val accepted = shortcutManager.requestPinShortcut(shortcut, callbackIntent.intentSender)

    return accepted && withTimeoutOrNull(3.seconds) {
      deferred.await().let { true }
    } ?: false
  }

  class ShortcutPinnedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      intent.getStringExtra(PENDING_INTENT_ID)?.let {
        pinningShortcuts.remove(it)?.complete(Unit)
      }
    }
  }
}