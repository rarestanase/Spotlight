package com.takusemba.spotlight.overlay

import android.view.View

sealed class Overlay {
  abstract val view: View
}

class FullScreenOverlay(
    override val view: View
) : Overlay()

class GravityOverlay(
    override val view: View,
    val gravity: Gravity,
    val margin: Float = 0.0f
) : Overlay() {
  enum class Gravity {
    TOP, BOTTOM
  }
}