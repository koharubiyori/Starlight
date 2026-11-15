package koharubiyori.starlight.util

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.createBitmap

fun Context.drawableToBitmap(
  @DrawableRes drawableRes: Int,
  @ColorInt backgroundColor: Int,
  @ColorInt foregroundColor: Int,
): Bitmap {
  val activityManager = getSystemService(ActivityManager::class.java)
  val size = (activityManager.launcherLargeIconSize * resources.displayMetrics.density).toInt()
  val bitmap = createBitmap(size, size)
  val canvas = Canvas(bitmap)

  // Draw the background
  val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = backgroundColor
  }

  canvas.drawRoundRect(
    0f,
    0f,
    size.toFloat(),
    size.toFloat(),
    0f,
    0f,
    backgroundPaint
  )

  // Get the Drawable and set the foreground color
  val drawable = AppCompatResources.getDrawable(
    this,
    drawableRes
  )!!.mutate()

  drawable.setTint(foregroundColor)

  // Draw the Drawable in the center of Bitmap
  val padding = size * 0.2f
  drawable.setBounds(
    padding.toInt(),
    padding.toInt(),
    (size - padding).toInt(),
    (size - padding).toInt()
  )

  drawable.draw(canvas)

  return bitmap
}