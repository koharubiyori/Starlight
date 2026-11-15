package koharubiyori.starlight.store.serializer

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import com.google.protobuf.InvalidProtocolBufferException
import koharubiyori.starlight.store.proto.SettingsStoreData
import java.io.InputStream
import java.io.OutputStream

object SettingsStoreDataSerializer : Serializer<SettingsStoreData> {
  override val defaultValue: SettingsStoreData = SettingsStoreData.getDefaultInstance()

  override suspend fun readFrom(input: InputStream): SettingsStoreData {
    try {
      return SettingsStoreData.parseFrom(input)
    } catch (exception: InvalidProtocolBufferException) {
      throw CorruptionException("Cannot read proto.", exception)
    }
  }

  override suspend fun writeTo(t: SettingsStoreData, output: OutputStream) {
    return t.writeTo(output)
  }
}