package koharubiyori.starlight.screen.settings

import android.util.Size
import android.app.Activity
import androidx.activity.ComponentActivity
import koharubiyori.starlight.R
import koharubiyori.starlight.component.commonDialog.CommonRadioItem
import org.xmlpull.v1.XmlPullParser
import java.util.Locale

object SettingsScreenUtils {
  fun Size.landscape() = if (width > height) Size(width, height) else Size(height, width)

  fun getNativeResolution(activity: ComponentActivity, notchSafe: Boolean): Size {
    val metrics = activity.windowManager.maximumWindowMetrics
    val bounds = metrics.bounds

    if (!notchSafe) {
      return Size(bounds.width(), bounds.height()).landscape()
    }

    val cutout = metrics.windowInsets.displayCutout ?: return Size(bounds.width(), bounds.height())

    val width = bounds.width() - cutout.safeInsetLeft - cutout.safeInsetRight
    val height = bounds.height() - cutout.safeInsetTop - cutout.safeInsetBottom

    return Size(width, height).landscape()
  }

  private fun languageListWithoutAuto(activity: Activity): List<CommonRadioItem<String>> {
    val parser = activity.resources.getXml(R.xml.locales_config)

    val languageTags = mutableListOf<String>().apply {
      var event = parser.eventType
      while (event != XmlPullParser.END_DOCUMENT) {
        if (event == XmlPullParser.START_TAG && parser.name == "locale") {
          val tag = parser.getAttributeValue(
            "http://schemas.android.com/apk/res/android",
            "name"
          )
          add(tag)
        }
        event = parser.next()
      }
    }

    return languageTags.map { CommonRadioItem(getSelfLanguageName(it), it) }
  }

  fun languageList(activity: Activity): List<CommonRadioItem<String>> {
    return listOf(CommonRadioItem(activity.getString(R.string.auto), "auto")) + languageListWithoutAuto(activity)
  }

  private fun getSelfLanguageName(tag: String) = when (tag) {
    "zh-Hans" -> "简体中文"
    "zh-Hant" -> "繁體中文"
    else -> Locale.forLanguageTag(tag).let { it.getDisplayLanguage(it) }
  }
}
