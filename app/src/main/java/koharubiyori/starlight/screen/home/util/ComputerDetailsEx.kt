package koharubiyori.starlight.screen.home.util

import koharubiyori.starlight.nvstream.http.ComputerDetails
import koharubiyori.starlight.nvstream.http.PairingManager
import koharubiyori.starlight.util.NetworkUtil
import koharubiyori.starlight.util.debugPrint
import java.security.cert.X509Certificate

class ComputerDetailsEx(
  details: ComputerDetails,
  var delayTime: Int = -1,
  var alias: String? = null,
  var connectionState: ConnectionState = ConnectionState.UNKNOWN
) : ComputerDetails(details) {
  enum class ConnectionState {
    UNKNOWN, CONNECTING, REACHABLE, UNREACHABLE
  }

  constructor() : this(ComputerDetails())
  constructor(detailsEx: ComputerDetailsEx) : this(detailsEx, detailsEx.delayTime, detailsEx.alias)

  suspend fun testDelay(): ComputerDetailsEx {
    val time = activeAddress?.let { NetworkUtil.testConnectionLatency(it.address, it.port) } ?: -1
    return copy(delayTime = time)
  }

  override fun update(details: ComputerDetails?) {
    super.update(details)
    (details as? ComputerDetailsEx)?.let {
      this.delayTime = it.delayTime
      this.alias = if (it.alias != null) it.alias else this.alias
      this.connectionState = if (it.connectionState != ConnectionState.UNKNOWN) it.connectionState else this.connectionState
    }
  }

  fun updateWithoutStates(details: ComputerDetails) {
    val backup = ComputerDetailsEx(this)
    update(details)

    state = backup.state
    pairState = backup.pairState
    runningGameId = backup.runningGameId
    delayTime = backup.delayTime
  }


  fun copy(details: ComputerDetails) = ComputerDetailsEx(this).apply { update(details) }

  fun copy(
    uuid: String? = this.uuid,
    name: String? = this.name,
    localAddress: AddressTuple? = this.localAddress,
    remoteAddress: AddressTuple? = this.remoteAddress,
    manualAddress: AddressTuple? = this.manualAddress,
    ipv6Address: AddressTuple? = this.ipv6Address,
    macAddress: String? = this.macAddress,
    serverCert: X509Certificate? = this.serverCert,
    state: State? = this.state,
    activeAddress: AddressTuple? = this.activeAddress,
    httpsPort: Int = this.httpsPort,
    externalPort: Int = this.externalPort,
    pairState: PairingManager.PairState? = this.pairState,
    runningGameId: Int = this.runningGameId,
    nvidiaServer: Boolean = this.nvidiaServer,
    delayTime: Int = this.delayTime,
    alias: String? = this.alias,
    connectionState: ConnectionState = this.connectionState
  ): ComputerDetailsEx {
    return ComputerDetailsEx().apply {
      this.uuid = uuid
      this.name = name
      this.localAddress = localAddress
      this.remoteAddress = remoteAddress
      this.manualAddress = manualAddress
      this.ipv6Address = ipv6Address
      this.macAddress = macAddress
      this.serverCert = serverCert
      this.state = state
      this.activeAddress = activeAddress
      this.httpsPort = httpsPort
      this.externalPort = externalPort
      this.pairState = pairState
      this.runningGameId = runningGameId
      this.nvidiaServer = nvidiaServer
      this.delayTime = delayTime
      this.alias = alias
      this.connectionState = connectionState
    }
  }

  fun toComputerDetails() = ComputerDetails().apply { update(this@ComputerDetailsEx) }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false
    return hashCode() == other.hashCode()
  }

  override fun hashCode(): Int {
    var result = delayTime + connectionState.ordinal + super.hashCode()
    result = 31 * result + (alias?.hashCode() ?: 0)
    return result
  }
}