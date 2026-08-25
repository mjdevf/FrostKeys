// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.content.edit
import androidx.core.view.isGone
import androidx.core.view.isVisible
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.R
import helium314.keyboard.latin.define.DebugFlags
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.setFloatingSize

/**
 * Handles positioning, dragging and resizing of the keyboard when floating mode is active.
 *
 * Originally ported from upstream HeliBoard (Helium314/HeliBoard, PR #2501 "Add floating keyboard"),
 * then reworked to window-based positioning (Gboard-style): the IME window itself is resized to the
 * floating panel and moved via WindowManager. Compared to the old margin-based approach this gives
 * free full-screen dragging, a frosted-glass blur that covers exactly the panel (the blur follows
 * the window background), a touch region that always matches the visible panel, and cheap drags
 * (a window update instead of a view-tree relayout).
 */
object FloatingKeyboardUtils {
    private val TAG = this::class.java.simpleName
    private var extraHeight = 0f

    private const val RESIZE_RELOAD_INTERVAL_MS = 120L

    @JvmStatic
    fun setFloating(view: View?) {
        if (view == null) return
        val decorLp = view.rootView.layoutParams as? WindowManager.LayoutParams ?: return
        val sv = Settings.getValues()
        val dm = view.resources.displayMetrics
        extraHeight = getSuggestionStripHeight(view.resources) + getFloatingHandleHeight(view.resources)
        // full-screen bounds — the panel may go anywhere on screen (Gboard-style), not only
        // into the region above the docked keyboard position
        val maxX = (dm.widthPixels - sv.mFloatingWidth).coerceAtLeast(0)
        val maxY = (dm.heightPixels - extraHeight.toInt() - sv.mFloatingHeight).coerceAtLeast(0)
        // center the keyboard by default until the user drags it somewhere else;
        // persist immediately so the insets computation (which reads without bounds) agrees
        val (x, y) = if (hasSavedPosition(view.context)) readPosition(view.context, maxX, maxY) else {
            val centered = maxX / 2 to maxY / 2
            savePosition(view.context, centered.first, centered.second)
            centered
        }
        if (DebugFlags.DEBUG_ENABLED)
            Log.d(TAG, "place floating window at $x, $y, width ${sv.mFloatingWidth}, height ${sv.mFloatingHeight}")
        applyFloatingWindowGeometry(view, decorLp, x, y, sv.mFloatingWidth)
        if (view.findViewById<View>(R.id.float_handle_container)?.isVisible == true)
            return
        view.findViewById<View>(R.id.float_handle_container)?.isVisible = true
        view.findViewById<ImageView>(R.id.drag_handle)?.setDragListener(view)
        view.findViewById<ImageView>(R.id.resize_handle)?.setResizeListener(view)
        // insets may have been applied before the floating state was known — re-run so the
        // wrapper drops the nav bar bottom padding (see KeyboardWrapperView.onApplyWindowInsets)
        view.findViewById<View>(R.id.keyboard_view_wrapper)?.requestApplyInsets()
    }

    @JvmStatic
    fun disableFloating(view: View?) {
        if (view == null) return
        val decorLp = view.rootView.layoutParams as? WindowManager.LayoutParams
        if (decorLp != null && decorLp.width != ViewGroup.LayoutParams.MATCH_PARENT) {
            // restore the full-width docked window geometry
            decorLp.gravity = Gravity.BOTTOM
            decorLp.x = 0
            decorLp.y = 0
            decorLp.width = ViewGroup.LayoutParams.MATCH_PARENT
            decorLp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            windowManager(view).updateViewLayout(view.rootView, decorLp)
        }
        if (DebugFlags.DEBUG_ENABLED)
            Log.d(TAG, "disable floating view")
        val lp = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT
        lp.height = ViewGroup.LayoutParams.MATCH_PARENT
        lp.leftMargin = 0
        lp.topMargin = 0
        view.requestLayout()
        view.findViewById<View>(R.id.float_handle_container)?.isGone = true
        // restore the nav bar bottom padding that floating mode drops (docked keyboard needs it)
        view.findViewById<View>(R.id.keyboard_view_wrapper)?.requestApplyInsets()
    }

    private fun applyFloatingWindowGeometry(view: View, decorLp: WindowManager.LayoutParams, x: Int, y: Int, width: Int) {
        if (decorLp.gravity == (Gravity.TOP or Gravity.START)
                && decorLp.x == x && decorLp.y == y && decorLp.width == width)
            return // already in place — avoid relayout churn from repeated setFloating calls
        decorLp.gravity = Gravity.TOP or Gravity.START
        decorLp.x = x
        decorLp.y = y
        decorLp.width = width
        decorLp.height = ViewGroup.LayoutParams.WRAP_CONTENT
        windowManager(view).updateViewLayout(view.rootView, decorLp)
    }

    private fun updateWindowPosition(view: View, x: Int, y: Int) {
        val decorLp = view.rootView.layoutParams as? WindowManager.LayoutParams ?: return
        if (decorLp.x == x && decorLp.y == y) return
        decorLp.x = x
        decorLp.y = y
        windowManager(view).updateViewLayout(view.rootView, decorLp)
    }

    private fun windowManager(view: View) =
        view.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    @JvmStatic
    fun getFloatingHandleHeight(resources: Resources) = resources.getDimension(R.dimen.config_floating_handle_height)

    /** [maxX] / [maxY] are the current bounds (window may have changed size, e.g. rotation) — position gets clamped and re-saved if it no longer fits. */
    @JvmStatic
    fun readPosition(context: Context, maxX: Int, maxY: Int): Pair<Int, Int> {
        val width = context.resources.displayMetrics.widthPixels
        val x = context.prefs().getInt(Settings.PREF_FLOATING_POS_X_PREFIX + width, width / 2)
        val y = context.prefs().getInt(Settings.PREF_FLOATING_POS_Y_PREFIX + width, context.resources.displayMetrics.heightPixels / 2)
        if (x > maxX || y > maxY)
            savePosition(context, maxX.coerceAtLeast(0), maxY.coerceAtLeast(0))
        return x.coerceIn(0, maxX.coerceAtLeast(0)) to y.coerceIn(0, maxY.coerceAtLeast(0))
    }

    private fun hasSavedPosition(context: Context): Boolean {
        val width = context.resources.displayMetrics.widthPixels
        return context.prefs().contains(Settings.PREF_FLOATING_POS_X_PREFIX + width)
    }

    private fun savePosition(context: Context, x: Int, y: Int) {
        val width = context.resources.displayMetrics.widthPixels
        context.prefs().edit {
            putInt(Settings.PREF_FLOATING_POS_X_PREFIX + width, x)
            putInt(Settings.PREF_FLOATING_POS_Y_PREFIX + width, y)
        }
    }

    private fun getSuggestionStripHeight(resources: Resources) =
        if (Settings.getValues().mToolbarMode == ToolbarMode.HIDDEN) 0
        else resources.getDimension(R.dimen.config_suggestions_strip_height).toInt()

    @SuppressLint("ClickableViewAccessibility")
    private fun View.setDragListener(view: View) {
        var startX = 0f
        var startY = 0f
        var positionX = 0
        var positionY = 0
        setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    (view.rootView.layoutParams as? WindowManager.LayoutParams)?.let {
                        positionX = it.x
                        positionY = it.y
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val sv = Settings.getValues()
                    val dm = context.resources.displayMetrics
                    positionX = (positionX + (event.rawX - startX)).toInt()
                        .coerceIn(0, (dm.widthPixels - sv.mFloatingWidth).coerceAtLeast(0))
                    positionY = (positionY + (event.rawY - startY)).toInt()
                        .coerceIn(0, (dm.heightPixels - extraHeight.toInt() - sv.mFloatingHeight).coerceAtLeast(0))
                    startX = event.rawX
                    startY = event.rawY
                    updateWindowPosition(view, positionX, positionY)
                    true
                }
                // persist only at gesture end — a prefs write per move event causes drag lag
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    savePosition(context, positionX, positionY)
                    true
                }
                else -> false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun View.setResizeListener(view: View) {
        var startX = 0f
        var startY = 0f
        var currentWidth = 0
        var currentHeight = 0
        var lastReload = 0L
        val scale = 3 / context.resources.displayMetrics.density
        setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    val sv = Settings.getValues()
                    currentWidth = sv.mFloatingWidth
                    currentHeight = sv.mFloatingHeight
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val sv = Settings.getValues()
                    val dm = context.resources.displayMetrics
                    val maxWidth = ((dm.widthPixels - decorX(view)) * 0.9f).toInt()
                    val maxHeight = ((dm.heightPixels - decorY(view) - extraHeight.toInt()) * 0.9f).toInt()
                    currentWidth = (currentWidth + (event.rawX - startX) / scale).toInt().coerceIn(150, maxWidth)
                    currentHeight = (currentHeight + (event.rawY - startY) / scale).toInt().coerceIn(100, maxHeight)
                    startX = event.rawX
                    startY = event.rawY
                    if (currentWidth == sv.mFloatingWidth && currentHeight == sv.mFloatingHeight)
                        return@setOnTouchListener true
                    // persist size (cheap memory write) so reloadKeyboard picks the new geometry up
                    setFloatingSize(context, currentWidth, currentHeight)
                    // live width preview on the window; full keyboard rebuild is throttled
                    val decorLp = view.rootView.layoutParams as? WindowManager.LayoutParams
                    if (decorLp != null && decorLp.width != currentWidth) {
                        decorLp.width = currentWidth
                        windowManager(view).updateViewLayout(view.rootView, decorLp)
                    }
                    if (SystemClock.uptimeMillis() - lastReload > RESIZE_RELOAD_INTERVAL_MS) {
                        lastReload = SystemClock.uptimeMillis()
                        KeyboardSwitcher.getInstance().reloadKeyboard()
                    }
                    true
                }
                // final rebuild with the exact final size
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    setFloatingSize(context, currentWidth, currentHeight)
                    KeyboardSwitcher.getInstance().reloadKeyboard()
                    true
                }
                else -> false
            }
        }
    }

    private fun decorX(view: View) =
        (view.rootView.layoutParams as? WindowManager.LayoutParams)?.x ?: 0

    private fun decorY(view: View) =
        (view.rootView.layoutParams as? WindowManager.LayoutParams)?.y ?: 0
}
