package koharubiyori.starlight.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.dataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import jakarta.inject.Inject
import jakarta.inject.Singleton
import koharubiyori.starlight.Globals
import koharubiyori.starlight.store.proto.ReminderStoreData
import koharubiyori.starlight.store.proto.ReminderStoreDataKt
import koharubiyori.starlight.store.proto.copy
import koharubiyori.starlight.store.serializer.ReminderStoreDataSerializer
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<ReminderStoreData> by dataStore(
  fileName = "reminder.pb",
  serializer = ReminderStoreDataSerializer
)

@Singleton
class ReminderStore @Inject constructor(
  @param:ApplicationContext private val context: Context
) {
  private val dataStore get() = context.dataStore
  val reminder get() = dataStore.data

  suspend fun updateReminder(block: ReminderStoreDataKt.Dsl.() -> Unit) {
    dataStore.updateData { it.copy(block) }
  }
}