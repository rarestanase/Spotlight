package com.takusemba.spotlight.blur

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.Activity
import android.content.Context.WINDOW_SERVICE
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.AsyncTask
import android.os.Build
import android.util.TypedValue
import android.view.Surface.ROTATION_0
import android.view.Surface.ROTATION_270
import android.view.Surface.ROTATION_90
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import com.takusemba.spotlight.SpotlightView
import com.takusemba.spotlight.blur.FastBlurHelper.doBlur
import kotlin.math.ceil

// inspired by
// https://github.com/tvbarthel/BlurDialogFragment/blob/master/lib/src/main/java/fr/tvbarthel/lib/blurdialogfragment/BlurDialogEngine.java
internal class BlurEngine(
    private val spotlightView: SpotlightView
) {
  private var mHoldingActivity = spotlightView.context as Activity
  private val mDownScaleFactor: Float = DEFAULT_BLUR_DOWN_SCALE_FACTOR
  private var mBluringTask: BlurAsyncTask? = null
  private var mUseRenderScript = DEFAULT_USE_RENDERSCRIPT
  private val mBlurRadius = DEFAULT_BLUR_RADIUS

  private var computedBlur: Bitmap? = null

  /**
   * Blur the given bitmap and add it to the activity.
   *
   * @param bkg  should be a bitmap of the background.
   * @param view background view.
   */
  private fun blur(bkg: Bitmap, view: View) {
    //overlay used to build scaled preview and blur background
    var overlay: Bitmap? = null

    //evaluate top offset due to status bar
    var statusBarHeight = 0
    if ((mHoldingActivity.window.attributes.flags
            and WindowManager.LayoutParams.FLAG_FULLSCREEN) == 0) { //not in fullscreen mode
      statusBarHeight = getStatusBarHeight()
    }

    // check if status bar is translucent to remove status bar offset in order to provide blur
    // on content bellow the status.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT && isStatusBarTranslucent()) {
      statusBarHeight = 0
    }

    val topOffset = statusBarHeight
    // evaluate bottom or right offset due to navigation bar.
    var bottomOffset = 0
    var rightOffset = 0
    var leftOffset = 0
    val navBarSize = getNavigationBarOffset()

    when (getScreenRotation()) {
      ROTATION_0 -> bottomOffset = navBarSize
      ROTATION_90 -> rightOffset = navBarSize
      ROTATION_270 -> leftOffset = navBarSize
    }



    //add offset to the source boundaries since we don't want to blur actionBar pixels
    val srcRect = Rect(
        leftOffset,
        topOffset,
        bkg.width - rightOffset,
        bkg.height - bottomOffset
    )
    //in order to keep the same ratio as the one which will be used for rendering, also
    //add the offset to the overlay.
    val height = ceil(
        (view.height - topOffset - bottomOffset) / mDownScaleFactor)
    val width = ceil((view.width - rightOffset - leftOffset) * height
        / (view.height - topOffset - bottomOffset))
    // Render script doesn't work with RGB_565
    overlay = if (mUseRenderScript) {
      Bitmap.createBitmap(width.toInt(), height.toInt(), Bitmap.Config.ARGB_8888)
    } else {
      Bitmap.createBitmap(width.toInt(), height.toInt(), Bitmap.Config.RGB_565)
    }
    //scale and draw background view on the canvas overlay
    val canvas = Canvas(overlay)
    val paint = Paint()
    paint.setFlags(Paint.FILTER_BITMAP_FLAG)
    //build drawing destination boundaries
    val destRect = RectF(0.0f, 0.0f, overlay.width.toFloat(), overlay.height.toFloat())
    //draw background from source area in source background to the destination area on the overlay
    canvas.drawBitmap(bkg, srcRect, destRect, paint)
    //apply fast blur on overlay
    computedBlur = if (mUseRenderScript) {
      RenderScriptBlurHelper.doBlur(overlay, mBlurRadius.toFloat(), true, mHoldingActivity)
    } else {
      doBlur(overlay, mBlurRadius, true)
    }
  }

  private fun getScreenRotation(): Int {
    return (spotlightView.context.getSystemService(WINDOW_SERVICE) as WindowManager)
        .defaultDisplay
        .rotation
  }

  /**
   * Async task used to process blur out of ui thread
   */
  private inner class BlurAsyncTask : AsyncTask<Void?, Void?, Void?>() {
    private var mBackground: Bitmap? = null
    private var mBackgroundView: View? = null

    override fun onPreExecute() {
      super.onPreExecute()
      val backgroundView = mHoldingActivity.window.decorView
      mBackgroundView = backgroundView
      //retrieve background view, must be achieved on ui thread since
      //only the original thread that created a view hierarchy can touch its views.
      val rect = Rect()
      backgroundView.getWindowVisibleDisplayFrame(rect)
      backgroundView.destroyDrawingCache()
      backgroundView.setDrawingCacheEnabled(true)
      backgroundView.buildDrawingCache(true)
      mBackground = backgroundView.getDrawingCache(true)
      /**
       * After rotation, the DecorView has no height and no width. Therefore
       * .getDrawingCache() return null. That's why we  have to force measure and layout.
       */
      if (mBackground == null) {
        backgroundView.measure(
            View.MeasureSpec.makeMeasureSpec(rect.width(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(rect.height(), View.MeasureSpec.EXACTLY)
        )
        backgroundView.layout(0, 0, backgroundView.getMeasuredWidth(),
            backgroundView.getMeasuredHeight())
        backgroundView.destroyDrawingCache()
        backgroundView.setDrawingCacheEnabled(true)
        backgroundView.buildDrawingCache(true)
        mBackground = backgroundView.getDrawingCache(true)
      }
    }

    protected override fun doInBackground(
        vararg params: Void?
    ): Void? { //process to the blue
      if (!isCancelled) {
        blur(mBackground!!, mBackgroundView!!)
      } else {
        return null
      }
      //clear memory
      mBackground!!.recycle()
      return null
    }

    @SuppressLint("NewApi") override fun onPostExecute(
        aVoid: Void?
    ) {
      super.onPostExecute(aVoid)
      mBackgroundView?.run {
        destroyDrawingCache()
        setDrawingCacheEnabled(false)
      }
      spotlightView.setBlurredBackground(computedBlur)
      mBackgroundView = null
      mBackground = null
    }
  }

  fun startProcessing() {
    val bluringTask = BlurAsyncTask()
    if (mHoldingActivity.window.decorView.isShown) {
      mBluringTask = bluringTask
      bluringTask.execute()
    } else {
      mHoldingActivity.window.decorView.viewTreeObserver.addOnPreDrawListener(
        object : ViewTreeObserver.OnPreDrawListener {
          override fun onPreDraw(): Boolean {
            mHoldingActivity.window.decorView.viewTreeObserver.removeOnPreDrawListener(this)
            mBluringTask = bluringTask
            bluringTask.execute()
            return true
          }
        }
      )
    }
  }

  fun stopProcessing() {
    mBluringTask?.cancel(true)
    mBluringTask = null
  }
  /**
   * retrieve status bar height in px
   *
   * @return status bar height in px
   */
  private fun getStatusBarHeight(): Int {
    var result = 0
    val resourceId = mHoldingActivity.resources
        .getIdentifier("status_bar_height", "dimen", "android")
    if (resourceId > 0) {
      result = mHoldingActivity.resources.getDimensionPixelSize(resourceId)
    }
    return result
  }

  /**
   * Retrieve offset introduce by the navigation bar.
   *
   * @return bottom offset due to navigation bar.
   */
  private fun getNavigationBarOffset(): Int {
    var result = 0
    val resources: Resources = mHoldingActivity.resources
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      val resourceId: Int = resources.getIdentifier("navigation_bar_height", "dimen", "android")
      if (resourceId > 0) {
        result = resources.getDimensionPixelSize(resourceId)
      }
    }
    return result
  }

  /**
   * Used to check if the status bar is translucent.
   *
   * @return true if the status bar is translucent.
   */
  @TargetApi(Build.VERSION_CODES.KITKAT)
  private fun isStatusBarTranslucent(): Boolean {
    val typedValue = TypedValue()
    val attribute = intArrayOf(android.R.attr.windowTranslucentStatus)
    val array = mHoldingActivity.obtainStyledAttributes(
        typedValue.resourceId,
        attribute
    )
    val isStatusBarTranslucent = array.getBoolean(0, false)
    array.recycle()
    return isStatusBarTranslucent
  }

  companion object {
    const val DEFAULT_BLUR_DOWN_SCALE_FACTOR = 4.0f
    const val DEFAULT_BLUR_RADIUS = 8
    const val DEFAULT_USE_RENDERSCRIPT = false
  }
}
