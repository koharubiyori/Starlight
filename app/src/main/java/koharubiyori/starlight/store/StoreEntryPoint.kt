package koharubiyori.starlight.store

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface StoreEntryPoint {
  fun reminder(): ReminderStore
  fun settings(): SettingsStore
  fun pairedComputer(): PairedComputerStore
}

