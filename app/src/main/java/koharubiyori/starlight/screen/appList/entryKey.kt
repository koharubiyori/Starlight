package koharubiyori.starlight.screen.appList

import android.content.Context
import android.content.Intent
import koharubiyori.starlight.MainActivity
import koharubiyori.starlight.nav.DeepLinkedNavEntryKey

private const val COMPUTER_UUID = "computerUuid"
private const val COMPUTER_NAME = "computerName"

class AppListEntryKey(
  val computerUuid: String,
  val computerName: String
) : DeepLinkedNavEntryKey {
  override fun toIntent(context: Context) = Intent(context, MainActivity::class.java).apply {
    action = Intent.ACTION_VIEW
    putExtra(DeepLinkedNavEntryKey.SCREEN_NAME, DeepLinkedNavEntryKey.APP_LIST)
    putExtra(COMPUTER_UUID, computerUuid)
    putExtra(COMPUTER_NAME, computerName)
  }

  override fun toString(): String {
    return "AppListEntryKey(computerUuid=$computerUuid, computerName=$computerName)"
  }

  companion object {
    fun fromIntent(intent: Intent): AppListEntryKey? {
      val computerUuid = intent.getStringExtra(COMPUTER_UUID) ?: return null
      val computerName = intent.getStringExtra(COMPUTER_NAME) ?: return null
      return AppListEntryKey(computerUuid, computerName)
    }
  }
}