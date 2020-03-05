package com.takusemba.spotlight.blur

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.renderscript.Allocation
import androidx.renderscript.Element
import androidx.renderscript.RSRuntimeException
import androidx.renderscript.RenderScript
import androidx.renderscript.ScriptIntrinsicBlur

// all credits to
// https://github.com/tvbarthel/BlurDialogFragment/blob/master/lib/src/main/java/fr/tvbarthel/lib/blurdialogfragment/RenderScriptBlurHelper.java
internal object RenderScriptBlurHelper {
  /**
   * Log cat
   */
  private val TAG = RenderScriptBlurHelper::class.java.simpleName

  /**
   * blur a given bitmap
   *
   * @param sentBitmap       bitmap to blur
   * @param radius           blur radius
   * @param canReuseInBitmap true if bitmap must be reused without blur
   * @param context          used by RenderScript, can be null if RenderScript disabled
   * @return blurred bitmap
   */
  fun doBlur(
      sentBitmap: Bitmap, radius: Float, canReuseInBitmap: Boolean, context: Context?
  ): Bitmap? {
    var bitmap: Bitmap
    bitmap = if (canReuseInBitmap) {
      sentBitmap
    } else {
      sentBitmap.copy(sentBitmap.config, true)
    }
    if (bitmap.config == Bitmap.Config.RGB_565) {
      // RenderScript hates RGB_565 so we convert it to ARGB_8888
      bitmap = convertRGB565toARGB888(bitmap)
    }
    try {
      val rs: RenderScript = RenderScript.create(context)
      val input: Allocation = Allocation.createFromBitmap(rs, bitmap,
          Allocation.MipmapControl.MIPMAP_NONE,
          Allocation.USAGE_SCRIPT)
      val output: Allocation = Allocation.createTyped(rs, input.getType())
      val script: ScriptIntrinsicBlur = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs))
      script.setRadius(radius)
      script.setInput(input)
      script.forEach(output)
      output.copyTo(bitmap)
      return bitmap
    } catch (e: RSRuntimeException) {
      Log.e(TAG,
          "RenderScript known error : https://code.google.com/p/android/issues/detail?id=71347 "
              + "continue with the FastBlur approach.")
    }
    return null
  }

  private fun convertRGB565toARGB888(bitmap: Bitmap): Bitmap {
    return bitmap.copy(Bitmap.Config.ARGB_8888, true)
  }
}