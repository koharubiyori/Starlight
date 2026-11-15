package koharubiyori.starlight.util

import android.content.Context
import android.widget.Toast
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private var queueCount = 0
private var lastSentText = ""

fun toast(context: Context, text: String, unlimited: Boolean = false) {
  globalMainCoroutineScope.launch {
    if (!unlimited && (queueCount >= 3 || (queueCount != 0 && lastSentText == text))) return@launch
    Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    lastSentText = text
    queueCount++
    delay(3000)
    queueCount--
  }
}
