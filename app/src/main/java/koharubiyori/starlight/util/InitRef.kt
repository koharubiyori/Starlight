package koharubiyori.starlight.util

import androidx.compose.runtime.saveable.SaverScope
import kotlin.reflect.KProperty
import androidx.compose.runtime.saveable.Saver as ISaver

class InitRef<T>(var value: T) {
  companion object {
    fun <T : Any> saver() = object : ISaver<InitRef<T>, Any> {
      override fun SaverScope.save(value: InitRef<T>): Any = value.value

      @Suppress("UNCHECKED_CAST")
      override fun restore(value: Any): InitRef<T> = InitRef(value as T)
    }
  }
}

operator fun <T> InitRef<T>.getValue(thisRef: Any?, property: KProperty<*>): T = value
operator fun <T> InitRef<T>.setValue(thisRef: Any?, property: KProperty<*>, newValue: T) {
  value = newValue
}
