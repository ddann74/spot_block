package com.spotblock.app.overlay

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import com.spotblock.app.R
import com.spotblock.app.diagnostics.DiagnosticLog

/**
 * A single floating "Download" button drawn over Spotify using an accessibility
 * overlay window (TYPE_ACCESSIBILITY_OVERLAY) - a window type only an
 * AccessibilityService can request, which is why this doesn't need the separate
 * "display over other apps" permission a regular app/Service would (same mechanism
 * TikTok Feed Filter's OverlayController uses). FLAG_NOT_FOCUSABLE keeps it from
 * stealing input focus from Spotify elsewhere on screen; the button itself still
 * receives taps at its own bounds.
 */
class OverlayController(
    private val service: AccessibilityService,
    private val diagnosticLog: DiagnosticLog,
    private val onDownloadTapped: () -> Unit
) {
    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: View? = null

    val isShowing: Boolean get() = overlayView != null

    fun show() {
        if (overlayView != null) return

        val view = LayoutInflater.from(service).inflate(R.layout.overlay_download_button, null)
        view.findViewById<View>(R.id.overlayDownloadButton).setOnClickListener { onDownloadTapped() }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            x = 16
        }

        try {
            windowManager.addView(view, params)
            overlayView = view
            diagnosticLog.log("OVERLAY", "shown")
        } catch (e: Exception) {
            // Some OEMs restrict accessibility overlays further than stock Android -
            // fail quietly rather than crash the whole service over a UI extra.
            diagnosticLog.logError("OVERLAY", "failed to show", e)
        }
    }

    fun hide() {
        val view = overlayView ?: return
        overlayView = null
        try {
            windowManager.removeView(view)
            diagnosticLog.log("OVERLAY", "hidden")
        } catch (e: Exception) {
            // View may already be gone (e.g. service interrupted) - nothing to clean up.
            diagnosticLog.logError("OVERLAY", "failed to hide (view may already be gone)", e)
        }
    }
}
