package koharubiyori.starlight.screen.appList.util

import android.content.Context
import com.google.protobuf.InvalidProtocolBufferException
import koharubiyori.starlight.nvstream.http.HostHttpResponseException
import koharubiyori.starlight.nvstream.http.NvApp
import koharubiyori.starlight.screen.home.util.ComputerDetailsEx
import koharubiyori.starlight.util.createNvHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.xmlpull.v1.XmlPullParserException
import timber.log.Timber
import java.io.IOException

fun loadCachedAppList(context: Context, computeDetails: ComputerDetailsEx, withoutCache: Boolean) = flow<List<NvApp>> {
  val cacheDir = context.cacheDir.resolve("appList").apply { mkdirs() }
  if (!withoutCache) {
    val cachedAppListFile = cacheDir.listFiles { it.name == computeDetails.uuid }?.getOrNull(0)
    if (cachedAppListFile != null) {
      try {
        val cachedAppList = AppList.parseFrom(cachedAppListFile.readBytes()).appsList.toNvAppList()
        emit(cachedAppList)
      } catch (ex: InvalidProtocolBufferException) {
        Timber.w(ex, "Failed to parse cached app list for computer: %s", computeDetails.name)
      }
    }
  }

  try {
    val latestAppList = createNvHttp(context, computeDetails).appList
    emit(latestAppList)

    val protoAppList = latestAppList.toProtoAppList()
    val apps = AppList.newBuilder().addAllApps(protoAppList).build().toByteArray()
    cacheDir.resolve(computeDetails.uuid).apply {
      createNewFile()
      writeBytes(apps)
    }
  } catch (ex: Exception) {
    when (ex) {
      is HostHttpResponseException,
      is IOException,
      is XmlPullParserException -> {
        Timber.w(ex, "Failed to load the app list for computer: %s", computeDetails.name)
      }
      else -> throw ex
    }
  }
}.flowOn(Dispatchers.IO)

private fun List<AppList.App>.toNvAppList() = map { NvApp(it.name, it.id, it.hdrSupported) }
private fun List<NvApp>.toProtoAppList() = map {
  AppList.App.newBuilder().apply {
    name = it.appName
    id = it.appId
    hdrSupported = it.isHdrSupported
  }.build()
}