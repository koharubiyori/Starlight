package koharubiyori.starlight.screen.streaming

import android.content.Context
import android.content.Intent
import koharubiyori.starlight.MainActivity
import koharubiyori.starlight.nav.DeepLinkedNavEntryKey

private const val COMPUTER_UUID = "computerUuid"
private const val APP_ID = "appId"
private const val APP_NAME = "appName"

class StreamingEntryKey(
  val computerUuid: String,
  val appId: Int,
  val appName: String
) : DeepLinkedNavEntryKey {
  override fun toIntent(context: Context) = Intent(context, MainActivity::class.java).apply {
    action = Intent.ACTION_VIEW
    putExtra(DeepLinkedNavEntryKey.SCREEN_NAME, DeepLinkedNavEntryKey.STREAMING)
    putExtra(COMPUTER_UUID, computerUuid)
    putExtra(APP_ID, appId)
    putExtra(APP_NAME, appName)
  }

  override fun toString(): String {
    return "StreamingEntryKey(computerUuid=$computerUuid, appId=$appId, appName=$appName)"
  }

  companion object {
    fun fromIntent(intent: Intent): StreamingEntryKey? {
      val computerUuid = intent.getStringExtra(COMPUTER_UUID) ?: return null
      val appId = intent.getIntExtra(APP_ID, -1).also { if (it == -1) return null }
      val appName = intent.getStringExtra(APP_NAME) ?: return null
      return StreamingEntryKey(computerUuid, appId, appName)
    }
  }
}