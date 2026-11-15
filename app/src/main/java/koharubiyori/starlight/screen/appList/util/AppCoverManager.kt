package koharubiyori.starlight.screen.appList.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import koharubiyori.starlight.nvstream.http.NvApp
import koharubiyori.starlight.screen.home.util.ComputerDetailsEx
import koharubiyori.starlight.screen.home.util.ComputerDetector.Companion.getLatest
import koharubiyori.starlight.util.createNvHttp
import koharubiyori.starlight.util.globalIoContinueScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.IOException

object AppCoverManager {
  suspend fun loadCover(context: Context, computerDetails: ComputerDetailsEx, app: NvApp): Bitmap? =
    withContext(Dispatchers.IO) {
      val cacheDir = context.cacheDir.resolve("appCover").apply { mkdirs() }
      val cachedCover =
        cacheDir.listFiles { it.name == cachedCoverId(computerDetails, app) }?.getOrNull(0)
      if (cachedCover != null) {
        val bitmap = withContext(Dispatchers.Default) { BitmapFactory.decodeFile(cachedCover.absolutePath) }
        if (bitmap != null) globalIoContinueScope.launch { loadAndCacheCover(context, cacheDir, computerDetails, app) }
        return@withContext bitmap
      }
      loadAndCacheCover(context, cacheDir, computerDetails, app)
    }

  private suspend fun loadAndCacheCover(
    context: Context,
    cacheDir: File,
    computerDetails: ComputerDetailsEx,
    app: NvApp
  ): Bitmap? = withContext(Dispatchers.IO) {
    val coverId = cachedCoverId(computerDetails, app)
    val bytes = try {
      val latestDetails = computerDetails.getLatest(context)
      val nvApp = createNvHttp(context, latestDetails)

      nvApp.getBoxArt(app).readBytes()
    } catch (ex: IOException) {
      Timber.w(ex, "Failed to load cover for app: %s", app.appId)
      return@withContext null
    }

    return@withContext withContext(Dispatchers.Default) {
      BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size
      )
    }?.also {
      cacheDir.resolve(coverId).apply {
        createNewFile()
        writeBytes(bytes)
      }
    }
  }
}

private fun cachedCoverId(computerDetails: ComputerDetailsEx, app: NvApp): String {
  return computerDetails.uuid + '_' + app.appId
}

fun Bitmap.isPlaceholderImage(): Boolean {
  return (width == 130 && height == 180) || (width == 628 && height == 888)
}
