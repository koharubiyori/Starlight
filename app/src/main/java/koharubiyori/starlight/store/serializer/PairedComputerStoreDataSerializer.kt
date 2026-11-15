package koharubiyori.starlight.store.serializer

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import com.google.protobuf.InvalidProtocolBufferException
import koharubiyori.starlight.store.proto.PairedComputerStoreData
import java.io.InputStream
import java.io.OutputStream

object PairedComputerStoreDataSerializer : Serializer<PairedComputerStoreData> {
  override val defaultValue: PairedComputerStoreData = PairedComputerStoreData.getDefaultInstance()

  override suspend fun readFrom(input: InputStream): PairedComputerStoreData {
    try {
      return PairedComputerStoreData.parseFrom(input)
    } catch (exception: InvalidProtocolBufferException) {
      throw CorruptionException("Cannot read proto.", exception)
    }
  }

  override suspend fun writeTo(t: PairedComputerStoreData, output: OutputStream) {
    return t.writeTo(output)
  }
}

