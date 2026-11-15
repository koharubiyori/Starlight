package koharubiyori.starlight.util

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException

object NetworkUtil {
  fun isActiveNetworkVpn(context: Context): Boolean {
    val connMgr = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    if (connMgr.activeNetwork == null) return false

    val netCaps = connMgr.getNetworkCapabilities(connMgr.activeNetwork) ?: return false

    return netCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
        !netCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
  }

  suspend fun testConnectionLatency(ip: String, port: Int, timeout: Int = 5000): Int = withContext(Dispatchers.IO) {
    val address = InetAddress.getByName(ip)
    val startTime = System.nanoTime()
    val connected = try {
      Socket().use { socket ->
        socket.connect(InetSocketAddress(address, port), timeout)
      }
      true
    } catch (ex: IOException) {
      Timber.d(ex, "TCP connection probe failed for $ip:$port")
      false
    }
    val elapsedMillis = (System.nanoTime() - startTime) / 1_000_000
    if (connected && elapsedMillis < timeout) elapsedMillis.toInt() else -1
  }

  // This is inaccurate when called immediately after the network status changes.
  fun isWifiConnected(context: Context): Boolean {
    val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = connectivityManager.activeNetwork ?: return false
    val networkCapabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
    return networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
  }

  fun isWifiEnabled(context: Context): Boolean {
    val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    return wifiManager.isWifiEnabled
  }

  fun isUsingMobileData(context: Context): Boolean {
    val connectivityManager =
      context.getSystemService(Context.CONNECTIVITY_SERVICE)
          as ConnectivityManager

    val network = connectivityManager.activeNetwork ?: return false
    val capabilities =
      connectivityManager.getNetworkCapabilities(network) ?: return false

    return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
  }

  @SuppressLint("DefaultLocale")
  fun getSelfIpInLan(context: Context, replaceLastPart: String? = null): String {
    val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
    val wifiInfo = wifiManager.connectionInfo
    val ipAddress = wifiInfo.ipAddress
    return String.format(
      "%d.%d.%d.%s",
      ipAddress and 0xFF,
      ipAddress shr 8 and 0xFF,
      ipAddress shr 16 and 0xFF,
      replaceLastPart ?: (ipAddress shr 24 and 0xFF).toString()
    )
  }

  fun macStringToBytes(macAddress: String): ByteArray {
    val macParts = macAddress.split(":")
    return ByteArray(6) { index ->
      macParts[index].toInt(16).toByte()
    }
  }

  fun openWifiSettings(context: Context) {
    val intent = Intent(Settings.ACTION_WIFI_SETTINGS)
    context.startActivity(intent)
  }

  suspend fun sendWakeOnLan(ipAddress: String, macAddress: String, port: Int): Boolean = withContext(Dispatchers.IO) {
    val ffBytes = (0..5).map { (0xff).toByte() }
    val macBytes = macAddress.split(":")
      .map { it.toInt(16).toByte() }
      .let { macByte -> (0..15).map { macByte } }
      .flatten()

    val magicPacket = (ffBytes + macBytes).toByteArray()
    val address = InetAddress.getByName(ipAddress)
    val packet = DatagramPacket(magicPacket, magicPacket.size, address, port)

    try {
      DatagramSocket().apply {
        broadcast = true
        send(packet)
        close()
      }

      return@withContext true
    } catch (ex: IOException) {
      Timber.e(ex, "Failed to send magic packet for Wake-On-Lan")
      return@withContext false
    }
  }

  fun isLocalIP(ipAddress: String): Boolean {
    return try {
      val inetAddress = InetAddress.getByName(ipAddress)
      val bytes = inetAddress.address

      when {
        (bytes[0].toInt() and 0xFF) == 10 -> true  // 10.x.x.x
        (bytes[0].toInt() and 0xFF) == 172 && (bytes[1].toInt() and 0xFF) in 16..31 -> true  // 172.16.x.x - 172.31.x.x
        (bytes[0].toInt() and 0xFF) == 192 && (bytes[1].toInt() and 0xFF) == 168 -> true  // 192.168.x.x
        else -> false
      }
    } catch (ex: UnknownHostException) {
      false
    }
  }
}
