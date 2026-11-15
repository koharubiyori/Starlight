package koharubiyori.starlight.store.serializer

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import com.google.protobuf.InvalidProtocolBufferException
import koharubiyori.starlight.store.proto.ReminderStoreData
import java.io.InputStream
import java.io.OutputStream

object ReminderStoreDataSerializer : Serializer<ReminderStoreData> {
  override val defaultValue: ReminderStoreData = ReminderStoreData.getDefaultInstance()

  override suspend fun readFrom(input: InputStream): ReminderStoreData {
    try {
      return ReminderStoreData.parseFrom(input)
    } catch (exception: InvalidProtocolBufferException) {
      throw CorruptionException("Cannot read proto.", exception)
    }
  }

  override suspend fun writeTo(t: ReminderStoreData, output: OutputStream) {
    return t.writeTo(output)
  }
}