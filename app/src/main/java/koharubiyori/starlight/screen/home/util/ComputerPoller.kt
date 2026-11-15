package koharubiyori.starlight.screen.home.util;

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import jakarta.inject.Inject
import jakarta.inject.Singleton
import koharubiyori.starlight.nvstream.http.PairingManager
import koharubiyori.starlight.screen.home.util.ComputerDetector.Companion.tryGetLatest
import koharubiyori.starlight.store.PairedComputerStore
import koharubiyori.starlight.util.debugPrint
import koharubiyori.starlight.util.globalMainCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Singleton
class ComputersPoller @Inject constructor(
  @param:ApplicationContext private val context: Context,
  val pairedComputerStore: PairedComputerStore,
  val computerDetector: ComputerDetector
) {
  private val _scannedComputersFlow = MutableStateFlow<List<ComputerDetailsEx>>(emptyList())
  val computersFlow = _scannedComputersFlow.combine(pairedComputerStore.computersFlow) { scanned, paired ->
    val merged = paired.map { pairedItem ->
      scanned
        .firstOrNull { it.uuid == pairedItem.uuid }
        ?.let { foundScanned -> pairedItem.copy(foundScanned) } ?: pairedItem
    }
    merged + scanned.filterNot { scannedItem -> paired.any { it.uuid == scannedItem.uuid } }
  }.stateIn(
    scope = globalMainCoroutineScope,
    started = SharingStarted.Eagerly,
    initialValue = emptyList()
  )
  private val _loadingPairedComputerFlow = MutableStateFlow(false)
  val loadingPairedComputerFlow = _loadingPairedComputerFlow.asSharedFlow()
  private var coroutine: CoroutineScope? = null
  private var pollingJob: Job? = null

//  private val detectedComputers = mutableListOf<ComputerDetailsEx>()

  fun start() {
    coroutine = CoroutineScope(Dispatchers.IO)
    restartPollingJob()
    Timber.i("Start to scan computers")
  }

  fun stop() {
    coroutine?.cancel()
    computerDetector.stop()
    Timber.i("Stop to scan computers")
  }

  fun restartPollingJob(intervalTime: Long = 1500) {
    computerDetector.start()
    pollingJob?.cancel()
    pollingJob = coroutine!!.launch {
      recompletePairedComputer()

      launch {
        computerDetector.computersFlow.collect { computers ->
          Timber.i("Detected computers: ${computers.map { 
            it.name + if (it.pairState == PairingManager.PairState.PAIRED) " (Paired)" else "" 
          }}")
          computers.forEach { computer ->
            if (!_scannedComputersFlow.value.any { computer.uuid == it.uuid }) {
              _scannedComputersFlow.emit(_scannedComputersFlow.value + computer)
            }
          }
        }
      }

      launch {
        while (true) {
          update()
          delay(intervalTime.milliseconds)
        }
      }
    }
  }

  suspend fun waitForActiveComputerByUuid(uuid: String, timeout: Duration): ComputerDetailsEx? {
    return withTimeoutOrNull(timeout) {
      computersFlow.firstOrNull { computers ->
        computers.any { it.activeAddress != null && it.uuid == uuid }
      }?.first { it.uuid == uuid }
    }
  }

  private suspend fun recompletePairedComputer() = coroutineScope {
    val nowComputers = computersFlow.first().filter { it.pairState == PairingManager.PairState.PAIRED }
    val pairedComputers = pairedComputerStore.computersFlow.first()
      .map { storeComputer ->
        nowComputers.firstOrNull { nowComputer -> nowComputer.uuid == storeComputer.uuid }?.let { storeComputer.update(it) }
        storeComputer.apply { connectionState = ComputerDetailsEx.ConnectionState.CONNECTING }
      }

    _loadingPairedComputerFlow.emit(true)
    _scannedComputersFlow.emit(pairedComputers)

    val scannedPairedComputers = pairedComputers
      .map { computer ->
        async {
          val result = computer.tryGetLatest(context)
          if (result != null) {
            pairedComputerStore.updateComputer(result, computer)
            result
          } else {
            computer.copy(delayTime = -1, connectionState = ComputerDetailsEx.ConnectionState.UNREACHABLE)
          }
        }
      }
      .awaitAll()

    _loadingPairedComputerFlow.emit(false)
    _scannedComputersFlow.emit(scannedPairedComputers)
  }

  suspend fun update() = coroutineScope {
    val updatedComputers = _scannedComputersFlow.first()
      .map {
        async {
          it.tryGetLatest(context)
            ?: it.copy(delayTime = -1, connectionState = ComputerDetailsEx.ConnectionState.UNREACHABLE)
        }
      }
      .awaitAll()
    _scannedComputersFlow.emit(updatedComputers)
  }
}
