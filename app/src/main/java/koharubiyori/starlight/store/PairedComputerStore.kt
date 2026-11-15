package koharubiyori.starlight.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.dataStore
import com.google.protobuf.kotlin.toByteString
import dagger.hilt.android.qualifiers.ApplicationContext
import jakarta.inject.Inject
import jakarta.inject.Singleton
import koharubiyori.starlight.nvstream.http.ComputerDetails
import koharubiyori.starlight.screen.home.util.ComputerDetailsEx
import koharubiyori.starlight.store.proto.PairedComputerStoreData
import koharubiyori.starlight.store.proto.PairedComputerStoreDataKt
import koharubiyori.starlight.store.proto.copy
import koharubiyori.starlight.store.proto.ipv6OrNull
import koharubiyori.starlight.store.proto.localOrNull
import koharubiyori.starlight.store.proto.manualOrNull
import koharubiyori.starlight.store.proto.remoteOrNull
import koharubiyori.starlight.store.serializer.PairedComputerStoreDataSerializer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

private val Context.dataStore: DataStore<PairedComputerStoreData> by dataStore(
  fileName = "paired_computer.pb",
  serializer = PairedComputerStoreDataSerializer
)

@Singleton
class PairedComputerStore @Inject constructor(
  @param:ApplicationContext private val context: Context
) {
  private val dataStore get() = context.dataStore

  val computersFlow = computers.map { it.map { it.toComputerDetailsEx() } }

  private val computers get() = dataStore.data.map { it.computersList }

  suspend fun updateComputer(computerDetails: ComputerDetailsEx, previousDetails: ComputerDetailsEx? = null) {
    val currentList = computers.first()
    val computerProto = computerDetails.toProto()
    val matchingIndex = currentList.indexOfFirst { it.uuid == computerDetails.uuid }
    val newList = if (matchingIndex != -1) {
      currentList.filterNot { it.uuid == computerDetails.uuid } + computerProto
    } else if (previousDetails != null) {
      val previousIndex = currentList.indexOf(previousDetails.toProto())
      if (previousIndex != -1) {
        currentList.toMutableList().apply { set(previousIndex, computerProto) }
      } else {
        currentList + computerProto
      }
    } else {
      currentList + computerProto
    }
    writeComputers(newList)
  }

  suspend fun removeComputer(uuid: String) {
    val currentList = computers.first()
    val newList = currentList.filterNot { it.uuid == uuid }
    writeComputers(newList)
  }

  suspend fun renameComputer(uuid: String, newAlias: String?) {
    val currentList = computers.first()
    val newList = currentList.map {
      if (it.uuid == uuid) it.copy { if (newAlias != null) alias = newAlias else clearAlias() } else it
    }
    writeComputers(newList)
  }

  suspend fun getComputerByUuid(uuid: String): ComputerDetailsEx? {
    val currentList = computers.first()
    val target = currentList.firstOrNull { it.uuid == uuid } ?: return null
    return target.toComputerDetailsEx()
  }

  private suspend fun writeComputers(value: List<PairedComputerStoreData.ComputerDetails>) {
    dataStore.updateData {
      it.copy {
        computers.clear()
        computers.addAll(value)
      }
    }
  }

  private fun ComputerDetailsEx.toProto() = PairedComputerStoreDataKt.computerDetails {
    val kt = this@toProto
    fun createAddresses(
      block: PairedComputerStoreDataKt.ComputerDetailsKt.AddressesKt.Dsl.() -> Unit
    ) = PairedComputerStoreDataKt.ComputerDetailsKt.addresses(block)
    fun createAddressTuple(
      block: PairedComputerStoreDataKt.ComputerDetailsKt.AddressesKt.AddressTupleKt.Dsl.() -> Unit
    ) = PairedComputerStoreDataKt.ComputerDetailsKt.AddressesKt.addressTuple(block)

    name = kt.name
    uuid = kt.uuid
    kt.macAddress?.let { mac = it }
    kt.serverCert?.encoded?.toByteString()?.let { serverCert = it }
    addresses = createAddresses {
      kt.localAddress?.let {
        local = createAddressTuple { host = it.address; port = it.port }
      }
      kt.remoteAddress?.let {
        remote = createAddressTuple { host = it.address; port = it.port }
      }
      kt.manualAddress?.let {
        manual = createAddressTuple { host = it.address; port = it.port }
      }
      kt.ipv6Address?.let {
        ipv6 = createAddressTuple { host = it.address; port = it.port }
      }
    }

    kt.alias?.let { alias = it }
  }


  private fun PairedComputerStoreData.ComputerDetails.toComputerDetailsEx() = ComputerDetailsEx().also {
    it.name = name
    it.uuid = uuid
    it.macAddress = if (hasMac()) mac else null
    it.serverCert = if (hasServerCert()) {
      CertificateFactory.getInstance("X.509")
        .generateCertificate(ByteArrayInputStream(serverCert.toByteArray())) as X509Certificate?
    } else null
    it.localAddress = addresses.localOrNull?.let { ComputerDetails.AddressTuple(it.host, it.port) }
    it.remoteAddress = addresses.remoteOrNull?.let { ComputerDetails.AddressTuple(it.host, it.port) }
    it.manualAddress = addresses.manualOrNull?.let { ComputerDetails.AddressTuple(it.host, it.port) }
    it.ipv6Address = addresses.ipv6OrNull?.let { ComputerDetails.AddressTuple(it.host, it.port) }

    it.alias = if (hasAlias()) alias else null
  }
}