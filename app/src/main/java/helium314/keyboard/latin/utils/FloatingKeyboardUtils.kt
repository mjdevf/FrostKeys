// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.define.DebugFlags
import helium314.keyboard.latin.FrostedGlassHelper
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.setFloatingSize

/**
 * Handles positioning, dragging and resizing of the keyboard when floating mode is active.
 *
 * Window-based positioning (Gboard-style): the IME window itself is resized to the floating panel
 * and moved via WindowManager. Full-screen dragging expands the window into a transparent layer
 * while keeping view bounds fixed, moving the panel smoothly with view translation.
 */
object FloatingKeyboardUtils {
    private val TAG = this::class.java.simpleName
    private var extraHeight = 0f

    private val hideHandler = Handler(Looper.getMainLooper())
    private var hideRunnable: Runnable? = null
    private const val AUTO_HIDE_DELAY_MS = 2500L

    private enum class Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    @JvmStatic
    fun setFloating(view: View?) {
        if (view == null) return
        val decorLp = view.rootView.layoutParams as? WindowManager.LayoutParams ?: return
        val sv = Settings.getValues()
        val dm = view.resources.displayMetrics
        extraHeight = getSuggestionStripHeight(view.resources) + getFloatingHandleHeight(view.resources) + getFloatingTopHandleHeight(view.resources)
        val navBarHeight = getNavigationBarHeight(view)
        val bottomBuffer = (navBarHeight + (16 * dm.density).toInt()).coerceAtLeast((48 * dm.density).toInt())
        // full-screen bounds with safe bottom buffer above the navigation bar / gesture zone
        val maxX = (dm.widthPixels - sv.mFloatingWidth).coerceAtLeast(0)
        val maxY = (dm.heightPixels - extraHeight.toInt() - sv.mFloatingHeight - bottomBuffer).coerceAtLeast(0)
        // center the keyboard by default and place it comfortably above the navbar until dragged
        val (x, y) = if (hasSavedPosition(view.context)) readPosition(view.context, maxX, maxY) else {
            val defaultX = maxX / 2
            val defaultY = (maxY * 0.7f).toInt().coerceIn(0, maxY)
            val centered = defaultX to defaultY
            savePosition(view.context, centered.first, centered.second)
            centered
        }
        if (DebugFlags.DEBUG_ENABLED)
            Log.d(TAG, "place floating window at $x, $y, width ${sv.mFloatingWidth}, height ${sv.mFloatingHeight}")

        // Fix layout params of the input view itself so it never stretches
        val lp = view.layoutParams as? FrameLayout.LayoutParams
        if (lp != null) {
            lp.gravity = Gravity.TOP or Gravity.START
            lp.width = sv.mFloatingWidth
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            view.layoutParams = lp
        }

        applyFloatingWindowGeometry(view, decorLp, x, y, sv.mFloatingWidth)

        view.findViewById<View>(R.id.float_handle_container)?.let {
            it.isVisible = true
            it.alpha = 1f
        }
        view.findViewById<View>(R.id.drag_handle_top)?.let {
            it.isVisible = true
            it.alpha = 1f
        }
        view.findViewById<View>(R.id.resize_handle_tl)?.isVisible = true
        view.findViewById<View>(R.id.resize_handle_tr)?.isVisible = true
        view.findViewById<View>(R.id.resize_handle_bl)?.isVisible = true
        view.findViewById<View>(R.id.resize_handle_br)?.isVisible = true

        wireHandles(view)
        scheduleAutoHide(view)

        // insets may have been applied before the floating state was known - re-run so the
        // wrapper drops the nav bar bottom padding (see KeyboardWrapperView.onApplyWindowInsets)
        view.findViewById<View>(R.id.keyboard_view_wrapper)?.requestApplyInsets()
    }

    @JvmStatic
    fun disableFloating(view: View?) {
        if (view == null) return
        cancelAutoHide()

        val decorLp = view.rootView.layoutParams as? WindowManager.LayoutParams
        if (decorLp != null) {
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

        view.translationX = 0f
        view.translationY = 0f
        view.rootView.translationX = 0f
        view.rootView.translationY = 0f

        val lp = view.layoutParams as? ViewGroup.MarginLayoutParams
        if (lp != null) {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            lp.leftMargin = 0
            lp.topMargin = 0
            view.layoutParams = lp
            view.requestLayout()
        }

        view.findViewById<View>(R.id.float_handle_container)?.let {
            it.isGone = true
            it.animate().cancel()
            it.alpha = 1f
        }
        view.findViewById<View>(R.id.drag_handle_top)?.let {
            it.isGone = true
            it.animate().cancel()
            it.alpha = 1f
        }
        view.findViewById<View>(R.id.resize_handle_tl)?.isGone = true
        view.findViewById<View>(R.id.resize_handle_tr)?.isGone = true
        view.findViewById<View>(R.id.resize_handle_bl)?.isGone = true
        view.findViewById<View>(R.id.resize_handle_br)?.isGone = true

        val handles = listOfNotNull(
            view.findViewById<View?>(R.id.drag_handle),
            view.findViewById<View?>(R.id.drag_handle_top),
            view.findViewById<View?>(R.id.float_handle_container),
            view.findViewById<View?>(R.id.resize_handle_tl),
            view.findViewById<View?>(R.id.resize_handle_tr),
            view.findViewById<View?>(R.id.resize_handle_bl),
            view.findViewById<View?>(R.id.resize_handle_br)
        )
        handles.forEach { clearSystemGestureExclusion(it) }

        // restore the nav bar bottom padding that floating mode drops (docked keyboard needs it)
        view.findViewById<View>(R.id.keyboard_view_wrapper)?.requestApplyInsets()
    }

    private fun wireHandles(view: View) {
        // tint the corner arcs with the theme accent (same color as the enter key)
        val accent = Settings.getValues().mColors.get(ColorType.ACTION_KEY_BACKGROUND)
        listOf(R.id.resize_handle_tl, R.id.resize_handle_tr, R.id.resize_handle_bl, R.id.resize_handle_br)
            .forEach { id ->
                view.findViewById<ImageView?>(id)?.let {
                    it.setColorFilter(accent)
                }
            }

        val handles = listOfNotNull(
            view.findViewById<View?>(R.id.drag_handle),
            view.findViewById<View?>(R.id.drag_handle_top),
            view.findViewById<View?>(R.id.float_handle_container),
            view.findViewById<View?>(R.id.resize_handle_tl),
            view.findViewById<View?>(R.id.resize_handle_tr),
            view.findViewById<View?>(R.id.resize_handle_bl),
            view.findViewById<View?>(R.id.resize_handle_br)
        )
        handles.forEach { registerSystemGestureExclusion(it) }

        view.findViewById<View>(R.id.drag_handle)?.setDragListener(view)
        view.findViewById<View>(R.id.drag_handle_top)?.setDragListener(view)
        view.findViewById<View>(R.id.resize_handle_tl)?.setCornerResizeListener(view, Corner.TOP_LEFT)
        view.findViewById<View>(R.id.resize_handle_tr)?.setCornerResizeListener(view, Corner.TOP_RIGHT)
        view.findViewById<View>(R.id.resize_handle_bl)?.setCornerResizeListener(view, Corner.BOTTOM_LEFT)
        view.findViewById<View>(R.id.resize_handle_br)?.setCornerResizeListener(view, Corner.BOTTOM_RIGHT)
    }

    private fun scheduleAutoHide(view: View) {
        cancelAutoHide()
        val r = Runnable {
            val handles = listOfNotNull(
                view.findViewById<View?>(R.id.float_handle_container),
                view.findViewById<View?>(R.id.drag_handle_top)
            )
            handles.forEach { h ->
                h.animate().alpha(0f).setDuration(350).start()
            }
        }
        hideRunnable = r
        hideHandler.postDelayed(r, AUTO_HIDE_DELAY_MS)
    }

    private fun showHandlesAndResetTimer(view: View) {
        cancelAutoHide()
        val handles = listOfNotNull(
            view.findViewById<View?>(R.id.float_handle_container),
            view.findViewById<View?>(R.id.drag_handle_top)
        )
        handles.forEach { h ->
            h.animate().cancel()
            h.alpha = 1f
        }
        scheduleAutoHide(view)
    }

    private fun cancelAutoHide() {
        hideRunnable?.let { hideHandler.removeCallbacks(it) }
        hideRunnable = null
    }

    private fun updateSystemGestureExclusion(view: View) {
        if (view.width <= 0 || view.height <= 0) return
        val rect = Rect(0, 0, view.width, view.height)
        ViewCompat.setSystemGestureExclusionRects(view, listOf(rect))
    }

    private fun registerSystemGestureExclusion(target: View) {
        target.addOnLayoutChangeListener { v, left, top, right, bottom, oldL, oldT, oldR, oldB ->
            if (right - left != oldR - oldL || bottom - top != oldB - oldT) {
                updateSystemGestureExclusion(v)
            }
        }
        target.post {
            updateSystemGestureExclusion(target)
        }
    }

    private fun clearSystemGestureExclusion(target: View) {
        ViewCompat.setSystemGestureExclusionRects(target, emptyList())
    }

    private fun applyFloatingWindowGeometry(view: View, decorLp: WindowManager.LayoutParams, x: Int, y: Int, width: Int) {
        if (decorLp.gravity == (Gravity.TOP or Gravity.START)
                && decorLp.x == x && decorLp.y == y && decorLp.width == width)
            return
        decorLp.gravity = Gravity.TOP or Gravity.START
        decorLp.x = x
        decorLp.y = y
        decorLp.width = width
        decorLp.height = ViewGroup.LayoutParams.WRAP_CONTENT
        windowManager(view).updateViewLayout(view.rootView, decorLp)
    }

    private fun updateWindowPosition(view: View, x: Int, y: Int) {
        val decorLp = view.rootView.layoutParams as? WindowManager.LayoutParams ?: return
        decorLp.gravity = Gravity.TOP or Gravity.START
        decorLp.x = x
        decorLp.y = y
        decorLp.width = Settings.getValues().mFloatingWidth
        decorLp.height = ViewGroup.LayoutParams.WRAP_CONTENT
        windowManager(view).updateViewLayout(view.rootView, decorLp)
    }

    private fun windowManager(view: View) =
        view.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private fun getNavigationBarHeight(view: View): Int {
        val rootInsets = ViewCompat.getRootWindowInsets(view.rootView)
        val navInsets = rootInsets?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom
            ?: rootInsets?.getInsets(WindowInsetsCompat.Type.systemGestures())?.bottom
        if (navInsets != null && navInsets > 0) return navInsets

        val res = view.resources
        val resId = res.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (resId > 0) res.getDimensionPixelSize(resId) else (48 * res.displayMetrics.density).toInt()
    }

    @JvmStatic
    fun getFloatingHandleHeight(resources: Resources) = resources.getDimension(R.dimen.config_floating_handle_height)

    @JvmStatic
    fun getFloatingTopHandleHeight(resources: Resources) = resources.getDimension(R.dimen.config_floating_top_handle_height)

    /** [maxX] / [maxY] are the current bounds (window may have changed size, e.g. rotation) - position gets clamped and re-saved if it no longer fits. */
    @JvmStatic
    fun readPosition(context: Context, maxX: Int, maxY: Int): Pair<Int, Int> {
        val width = context.resources.displayMetrics.widthPixels
        val defaultY = (maxY * 0.7f).toInt().coerceIn(0, maxY)
        val x = context.prefs().getInt(Settings.PREF_FLOATING_POS_X_PREFIX + width, width / 2)
        val y = context.prefs().getInt(Settings.PREF_FLOATING_POS_Y_PREFIX + width, defaultY)
        if (x > maxX || y > maxY)
            savePosition(context, x.coerceIn(0, maxX.coerceAtLeast(0)), y.coerceIn(0, maxY.coerceAtLeast(0)))
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

    /**
     * Smooth Gboard-style drag: on touch-down the window expands into a transparent full-screen
     * layer so the panel can be moved with pure view translation (no window updates, no relayout,
     * no stretching); the final position is committed with a single window update on touch-up.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun View.setDragListener(view: View) {
        var startX = 0f
        var startY = 0f
        var x0 = 0
        var y0 = 0
        var curX = 0
        var curY = 0
        setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                    showHandlesAndResetTimer(view)

                    startX = event.rawX
                    startY = event.rawY
                    (view.rootView.layoutParams as? WindowManager.LayoutParams)?.let {
                        x0 = it.x
                        y0 = it.y
                    }
                    curX = x0
                    curY = y0

                    // Keep InputView width strictly fixed so it never stretches in the expanded window
                    val sv = Settings.getValues()
                    val lp = view.layoutParams as? FrameLayout.LayoutParams
                    if (lp != null) {
                        lp.gravity = Gravity.TOP or Gravity.START
                        lp.width = sv.mFloatingWidth
                        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                        view.layoutParams = lp
                    }

                    FrostedGlassHelper.setDragBackgroundSuppressed(view, true)

                    // Expand window into full-screen transparent layer for unrestricted dragging
                    val decorLp = view.rootView.layoutParams as? WindowManager.LayoutParams
                    if (decorLp != null && decorLp.width != ViewGroup.LayoutParams.MATCH_PARENT) {
                        decorLp.gravity = Gravity.TOP or Gravity.START
                        decorLp.x = 0
                        decorLp.y = 0
                        decorLp.width = ViewGroup.LayoutParams.MATCH_PARENT
                        decorLp.height = ViewGroup.LayoutParams.MATCH_PARENT
                        windowManager(view).updateViewLayout(view.rootView, decorLp)
                    }

                    view.translationX = curX.toFloat()
                    view.translationY = curY.toFloat()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                    showHandlesAndResetTimer(view)

                    val sv = Settings.getValues()
                    val dm = context.resources.displayMetrics
                    val navBarHeight = getNavigationBarHeight(view)
                    val bottomBuffer = (navBarHeight + (16 * dm.density).toInt()).coerceAtLeast((48 * dm.density).toInt())
                    val maxX = (dm.widthPixels - sv.mFloatingWidth).coerceAtLeast(0)
                    val maxY = (dm.heightPixels - extraHeight.toInt() - sv.mFloatingHeight - bottomBuffer).coerceAtLeast(0)

                    curX = (x0 + (event.rawX - startX)).toInt().coerceIn(0, maxX)
                    curY = (y0 + (event.rawY - startY)).toInt().coerceIn(0, maxY)

                    view.translationX = curX.toFloat()
                    view.translationY = curY.toFloat()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    parent?.requestDisallowInterceptTouchEvent(false)
                    view.parent?.requestDisallowInterceptTouchEvent(false)

                    view.translationX = 0f
                    view.translationY = 0f

                    updateWindowPosition(view, curX, curY)
                    savePosition(context, curX, curY)
                    FrostedGlassHelper.setDragBackgroundSuppressed(view, false)
                    scheduleAutoHide(view)
                    true
                }
                else -> false
            }
        }
    }

    /**
     * Gboard-style corner resize: dragging a corner changes width/height while the opposite
     * corner stays anchored. Window updates continuously during move for instant visual feedback;
     * full keyboard rebuild runs once on touch-up.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun View.setCornerResizeListener(view: View, corner: Corner) {
        var startX = 0f
        var startY = 0f
        var x0 = 0
        var y0 = 0
        var w0 = 0
        var h0 = 0
        var curX = 0
        var curY = 0
        var curW = 0
        var curH = 0
        setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                    showHandlesAndResetTimer(view)

                    startX = event.rawX
                    startY = event.rawY
                    val sv = Settings.getValues()
                    (view.rootView.layoutParams as? WindowManager.LayoutParams)?.let {
                        x0 = it.x
                        y0 = it.y
                    }
                    w0 = sv.mFloatingWidth
                    h0 = sv.mFloatingHeight
                    curX = x0
                    curY = y0
                    curW = w0
                    curH = h0

                    FrostedGlassHelper.setDragBackgroundSuppressed(view, true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                    showHandlesAndResetTimer(view)

                    val sv = Settings.getValues()
                    val dm = context.resources.displayMetrics
                    val navBarHeight = getNavigationBarHeight(view)
                    val bottomBuffer = (navBarHeight + (16 * dm.density).toInt()).coerceAtLeast((48 * dm.density).toInt())
                    val dx = (event.rawX - startX).toInt()
                    val dy = (event.rawY - startY).toInt()
                    val growRight = corner == Corner.TOP_RIGHT || corner == Corner.BOTTOM_RIGHT
                    val growDown = corner == Corner.BOTTOM_LEFT || corner == Corner.BOTTOM_RIGHT
                    curW = (w0 + if (growRight) dx else -dx).coerceIn(150, dm.widthPixels)
                    curH = (h0 + if (growDown) dy else -dy).coerceIn(100, dm.heightPixels - extraHeight.toInt() - bottomBuffer)
                    curX = if (growRight) x0 else x0 + (w0 - curW)
                    curY = if (growDown) y0 else y0 + (h0 - curH)

                    // keep the panel on screen
                    curX = curX.coerceIn(0, (dm.widthPixels - curW).coerceAtLeast(0))
                    curY = curY.coerceIn(0, (dm.heightPixels - extraHeight.toInt() - bottomBuffer - curH).coerceAtLeast(0))

                    if (curW == sv.mFloatingWidth && curH == sv.mFloatingHeight && curX == x0 && curY == y0)
                        return@setOnTouchListener true

                    setFloatingSize(context, curW, curH)

                    val lp = view.layoutParams as? FrameLayout.LayoutParams
                    if (lp != null && lp.width != curW) {
                        lp.width = curW
                        view.layoutParams = lp
                    }

                    val decorLp = view.rootView.layoutParams as? WindowManager.LayoutParams
                    if (decorLp != null && (decorLp.x != curX || decorLp.y != curY || decorLp.width != curW)) {
                        decorLp.x = curX
                        decorLp.y = curY
                        decorLp.width = curW
                        windowManager(view).updateViewLayout(view.rootView, decorLp)
                    }
                    view.requestLayout()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    parent?.requestDisallowInterceptTouchEvent(false)
                    view.parent?.requestDisallowInterceptTouchEvent(false)

                    setFloatingSize(context, curW, curH)
                    KeyboardSwitcher.getInstance().reloadKeyboard()
                    FrostedGlassHelper.setDragBackgroundSuppressed(view, false)
                    scheduleAutoHide(view)
                    true
                }
                else -> false
            }
        }
    }
}