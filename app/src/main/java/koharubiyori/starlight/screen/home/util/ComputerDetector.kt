package koharubiyori.starlight.screen.home.util

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import jakarta.inject.Inject
import jakarta.inject.Singleton
import koharubiyori.starlight.nvstream.AndroidCryptoProvider
import koharubiyori.starlight.nvstream.NvConnection
import koharubiyori.starlight.nvstream.http.ComputerDetails
import koharubiyori.starlight.nvstream.http.NvHTTP
import koharubiyori.starlight.nvstream.mdns.JmDNSDiscoveryAgent
import koharubiyori.starlight.nvstream.mdns.MdnsComputer
import koharubiyori.starlight.nvstream.mdns.MdnsDiscoveryAgent
import koharubiyori.starlight.nvstream.mdns.MdnsDiscoveryListener
import koharubiyori.starlight.nvstream.mdns.NsdManagerDiscoveryAgent
import koharubiyori.starlight.store.PairedComputerStore
import koharubiyori.starlight.util.NetworkUtil
import koharubiyori.starlight.util.debugPrint
import koharubiyori.starlight.util.uniqueId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParserException
import timber.log.Timber
import java.io.IOException
import java.net.Inet4Address
import kotlin.time.Duration.Companion.seconds

@Singleton
class ComputerDetector @Inject constructor(
  @param:ApplicationContext private val context: Context,
  private val pairedComputerStore: PairedComputerStore
) {
  private val _computersFlow = MutableStateFlow<List<ComputerDetailsEx>>(emptyList())
  val computersFlow = _computersFlow.asStateFlow()
  private var discoveryAgent: MdnsDiscoveryAgent? = null
  private var coroutine: CoroutineScope? = null
  private val mdnsKeyToCachedReachedComputers = mutableMapOf<String, ComputerDetailsEx>()

  fun start() {
    stop()
    coroutine = CoroutineScope(Dispatchers.IO)
    resetDiscoveryAgent()
    discoveryAgent?.startDiscovery(1000)
  }

  fun stop() {
    discoveryAgent?.stopDiscovery()
    coroutine?.cancel()
  }
  
  private fun resetDiscoveryAgent() {
    discoveryAgent?.stopDiscovery()

    val listener = object : MdnsDiscoveryListener {
      override fun notifyComputerAdded(computer: MdnsComputer) {
        coroutine!!.launch {
          val computers = discoveryAgent!!.computerSet.map {
            val cacheKey = it.toString()
            if (mdnsKeyToCachedReachedComputers.containsKey(cacheKey)) {
              mdnsKeyToCachedReachedComputers[cacheKey]
            } else {
              it.toComputerDetails()?.also {
                mdnsKeyToCachedReachedComputers[cacheKey] = it
              }
            }
          }.filterNotNull()
          if (computers.isNotEmpty()) _computersFlow.value = computers
        }
      }

      override fun notifyDiscoveryFailure(e: Exception?) {
        Timber.e(e)
      }
    }

    // Prior to Android 14, NsdManager doesn't provide all the capabilities needed for parity
    // with jmDNS (specifically handling multiple addresses for a single service). There are
    // also documented reliability bugs early in the Android 4.x series shortly after it was
    // introduced. The benefit of using NsdManager over jmDNS is that it works correctly in
    // environments where mDNS proxying is required, like ChromeOS, WSA, and the emulator.
    //
    // As such, we use the jmDNS-based MdnsDiscoveryAgent prior to Android 14 and NsdManager
    // on Android 14 and above.
    discoveryAgent = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      JmDNSDiscoveryAgent(context, listener)
    } else {
      NsdManagerDiscoveryAgent(context, listener)
    }
  }

  private suspend fun MdnsComputer.toComputerDetails(): ComputerDetailsEx? {
    val computer = this
    val newDetails = ComputerDetailsEx().apply {
      computer.localAddress?.let {
        localAddress = ComputerDetails.AddressTuple(it.hostAddress, computer.port)
        if (it is Inet4Address) {
          tryObtainRemoteAddress(context)?.let { remoteAddress = ComputerDetails.AddressTuple(it, computer.port) }
        }
      }
      computer.ipv6Address?.let { ipv6Address = ComputerDetails.AddressTuple(it.hostAddress, computer.port)
      }
    }

    val details = newDetails.tryGetLatest(context) ?: return null
    pairedComputerStore.getComputerByUuid(details.uuid)?.let { pairedDetails -> details.updateWithoutStates(pairedDetails) }

    return details
  }

  companion object {
    suspend fun ComputerDetailsEx.tryGetLatest(context: Context): ComputerDetailsEx? {
      val latestDetails = tryProbeComputerByAddresses(context, this) ?: return null
      update(latestDetails)
      this.connectionState = ComputerDetailsEx.ConnectionState.REACHABLE
      return testDelay()
    }

    suspend fun ComputerDetailsEx.getLatest(context: Context): ComputerDetailsEx {
      return tryGetLatest(context) ?: this
    }
  }
}

private suspend fun tryObtainRemoteAddress(context: Context): String? = withContext(Dispatchers.IO) {
  val activeNetworkIsVpn = NetworkUtil.isActiveNetworkVpn(context)
  var boundToNetwork = false
  val connectionManager = context.getSystemService(ConnectivityManager::class.java)

  // Check if we're currently connected to a VPN which may send our
  // STUN request from an unexpected interface
  if (activeNetworkIsVpn) {
    // On Lollipop or later, we can bind our process to the underlying interface
    // to ensure our STUN request goes out on that interface or not at all (which is
    // preferable to getting a VPN endpoint address back).

    val wifiNet = connectionManager.allNetworks.firstOrNull {
      val netCaps = connectionManager.getNetworkCapabilities(it) ?: return@firstOrNull false
      val cellular = netCaps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
      val vpn = netCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
      !cellular && !vpn
    }

    boundToNetwork = wifiNet?.let { connectionManager.bindProcessToNetwork(it) } ?: false
  }

  try {
    // Perform the STUN request if we're not on a VPN or if we bound to a network
    return@withContext if (!activeNetworkIsVpn || boundToNetwork)
      NvConnection.findExternalAddressForMdns("stun.moonlight-stream.org", 3478) else null
  } finally {
    // Unbind from the network
    if (boundToNetwork) connectionManager.bindProcessToNetwork(null)
  }
}

private suspend fun tryProbeComputerByIp(context: Context, details: ComputerDetailsEx, address: ComputerDetails.AddressTuple): ComputerDetailsEx? = withContext(
  Dispatchers.IO) {
  try {
    // If the current address's port number matches the active address's port number, we can also assume
    // the HTTPS port will also match. This assumption is currently safe because Sunshine sets all ports
    // as offsets from the base HTTP port and doesn't allow custom HttpsPort responses for WAN vs LAN.
    val portMatchesActiveAddress =
      details.state == ComputerDetails.State.ONLINE && details.activeAddress != null && address.port == details.activeAddress.port

    val nvHttp = NvHTTP(
      address,
      if (portMatchesActiveAddress) details.httpsPort else 0,
      uniqueId(context),
      details.serverCert,
      AndroidCryptoProvider(context)
    )

    // If this PC is currently online at this address, extend the timeouts to allow more time for the PC to respond.
    val isLikelyOnline =
      details.state === ComputerDetails.State.ONLINE && address.equals(details.activeAddress)

    val newDetails: ComputerDetails = nvHttp.getComputerDetails(isLikelyOnline)

    // Check if this is the PC we expected
    if (newDetails.uuid == null) {
      Timber.w("Polling returned no UUID!")
      return@withContext null
    } else if (details.uuid != null && !details.uuid.equals(newDetails.uuid)) {
      // We got the wrong PC!
      Timber.w("Polling returned the wrong PC!")
      return@withContext null
    }

    ComputerDetailsEx(newDetails)
  } catch (e: XmlPullParserException) {
    Timber.e(e)
    return@withContext null
  } catch (e: IOException) {
    return@withContext null
  }
}

@OptIn(ExperimentalCoroutinesApi::class)
suspend fun tryProbeComputerByAddresses(context: Context, details: ComputerDetailsEx): ComputerDetailsEx? = coroutineScope {
  fun createPollingTask(address: ComputerDetails.AddressTuple) = CompletableDeferred<ComputerDetailsEx>().apply {
    launch {
      val result = tryProbeComputerByIp(context, details, address)
      if (result != null) {
        result.activeAddress = address
        complete(result)
      } else cancel()
    }
  }

  val tasks = setOf(details.localAddress, details.manualAddress, details.remoteAddress, details.ipv6Address)
    .filterNotNull()
    .map { createPollingTask(it) }

  try {
    select {
      for(item in tasks) item.onAwait { it }
      onTimeout(20.seconds) { null }
    }
  } finally {
    tasks.forEach { it.cancel() }
  }
}
