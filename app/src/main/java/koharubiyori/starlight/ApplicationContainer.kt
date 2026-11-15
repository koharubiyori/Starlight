package koharubiyori.starlight

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import koharubiyori.starlight.initialization.initializeOnCreate
import timber.log.Timber

@HiltAndroidApp
class ApplicationContainer : Application() {
  override fun onCreate() {
    super.onCreate()
//    FirebaseCrashlytics.getInstance().isCrashlyticsCollectionEnabled = !BuildConfig.DEBUG
    Timber.plant(Timber.DebugTree())
    initializeOnCreate()
  }
}
