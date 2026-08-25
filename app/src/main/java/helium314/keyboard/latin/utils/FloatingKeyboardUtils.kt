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
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.content.edit
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
 * Originally ported from upstream HeliBoard (Helium314/HeliBoard, PR #2501 "Add floating keyboard"),
 * then reworked to window-based positioning (Gboard-style): the IME window itself is resized to the
 * floating panel and moved via WindowManager. Compared to the old margin-based approach this gives
 * free full-screen dragging, a frosted-glass blur that covers exactly the panel (the blur follows
 * the window background), a touch region that always matches the visible panel, and cheap drags.
 *
 * Dragging is extra smooth: on touch-down the window expands to a transparent full-screen layer,
 * the panel is moved with plain view translation (render thread only, zero window updates) and the
 * final position is committed with a single window update on touch-up. Resizing uses the four
 * Gboard-style corner handles, keeping the opposite corner anchored.
 */
object FloatingKeyboardUtils {
    private val TAG = this::class.java.simpleName
    private var extraHeight = 0f

    private const val RESIZE_RELOAD_INTERVAL_MS = 120L

    private enum class Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

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
        view.findViewById<View>(R.id.resize_handle_tl)?.isVisible = true
        view.findViewById<View>(R.id.resize_handle_tr)?.isVisible = true
        wireHandles(view)
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
        view.rootView.translationX = 0f
        view.rootView.translationY = 0f
        val lp = view.layoutParams as? ViewGroup.MarginLayoutParams
        if (lp != null) {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT
            lp.leftMargin = 0
            lp.topMargin = 0
            view.requestLayout()
        }
        view.findViewById<View>(R.id.float_handle_container)?.isGone = true
        view.findViewById<View>(R.id.resize_handle_tl)?.isGone = true
        view.findViewById<View>(R.id.resize_handle_tr)?.isGone = true
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
        view.findViewById<View>(R.id.drag_handle)?.setDragListener(view)
        view.findViewById<View>(R.id.resize_handle_tl)?.setCornerResizeListener(view, Corner.TOP_LEFT)
        view.findViewById<View>(R.id.resize_handle_tr)?.setCornerResizeListener(view, Corner.TOP_RIGHT)
        view.findViewById<View>(R.id.resize_handle_bl)?.setCornerResizeListener(view, Corner.BOTTOM_LEFT)
        view.findViewById<View>(R.id.resize_handle_br)?.setCornerResizeListener(view, Corner.BOTTOM_RIGHT)
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
        if (decorLp.x == x && decorLp.y == y && decorLp.width != ViewGroup.LayoutParams.MATCH_PARENT) return
        decorLp.gravity = Gravity.TOP or Gravity.START
        decorLp.x = x
        decorLp.y = y
        decorLp.width = Settings.getValues().mFloatingWidth
        decorLp.height = ViewGroup.LayoutParams.WRAP_CONTENT
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

    /**
     * Smooth Gboard-style drag: on touch-down the window expands into a transparent full-screen
     * layer so the panel can be moved with pure view translation (no window updates, no relayout
     * per move event); the final position is committed with a single window update on touch-up.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun View.setDragListener(view: View) {
        var startX = 0f
        var startY = 0f
        var x0 = 0
        var y0 = 0
        var curX = 0
        var curY = 0
        var baseTranslationY = 0f
        setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    (view.rootView.layoutParams as? WindowManager.LayoutParams)?.let {
                        x0 = it.x
                        y0 = it.y
                    }
                    curX = x0
                    curY = y0
                    // anchor the panel to the bottom of the soon-expanded window
                    (view.layoutParams as? FrameLayout.LayoutParams)?.let {
                        it.gravity = Gravity.BOTTOM
                        view.layoutParams = it
                    }
                    FrostedGlassHelper.setDragBackgroundSuppressed(view, true)
                    val dm = context.resources.displayMetrics
                    // expand into a transparent full-screen layer — translation then moves the
                    // panel anywhere without touching the window until touch-up
                    val decorLp = view.rootView.layoutParams as? WindowManager.LayoutParams
                    if (decorLp != null && decorLp.width != ViewGroup.LayoutParams.MATCH_PARENT) {
                        decorLp.gravity = Gravity.TOP or Gravity.START
                        decorLp.x = 0
                        decorLp.y = 0
                        decorLp.width = ViewGroup.LayoutParams.MATCH_PARENT
                        decorLp.height = ViewGroup.LayoutParams.MATCH_PARENT
                        windowManager(view).updateViewLayout(view.rootView, decorLp)
                    }
                    // keep the panel visually where it was: it now sits at the window bottom
                    baseTranslationY = (y0 - (dm.heightPixels - view.height)).toFloat()
                    view.rootView.translationX = x0.toFloat()
                    view.rootView.translationY = baseTranslationY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val sv = Settings.getValues()
                    val dm = context.resources.displayMetrics
                    curX = (x0 + (event.rawX - startX)).toInt()
                        .coerceIn(0, (dm.widthPixels - sv.mFloatingWidth).coerceAtLeast(0))
                    curY = (y0 + (event.rawY - startY)).toInt()
                        .coerceIn(0, (dm.heightPixels - extraHeight.toInt() - sv.mFloatingHeight).coerceAtLeast(0))
                    // render-thread only — no window updates, no relayout, no IPC per move
                    view.rootView.translationX = curX.toFloat()
                    view.rootView.translationY = (curY - (dm.heightPixels - view.height)).toFloat()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.rootView.translationX = 0f
                    view.rootView.translationY = 0f
                    updateWindowPosition(view, curX, curY)
                    savePosition(context, curX, curY)
                    FrostedGlassHelper.setDragBackgroundSuppressed(view, false)
                    true
                }
                else -> false
            }
        }
    }

    /**
     * Gboard-style corner resize: dragging a corner changes width/height while the opposite
     * corner stays anchored. Size persists (cheap memory write) so the throttled keyboard
     * rebuilds pick the new geometry up; a final rebuild runs on touch-up.
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
        var lastReload = 0L
        val scale = 3 / context.resources.displayMetrics.density
        setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
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
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val sv = Settings.getValues()
                    val dm = context.resources.displayMetrics
                    val dx = ((event.rawX - startX) / scale).toInt()
                    val dy = ((event.rawY - startY) / scale).toInt()
                    val growRight = corner == Corner.TOP_RIGHT || corner == Corner.BOTTOM_RIGHT
                    val growDown = corner == Corner.BOTTOM_LEFT || corner == Corner.BOTTOM_RIGHT
                    curW = (w0 + if (growRight) dx else -dx).coerceIn(150, dm.widthPixels)
                    curH = (h0 + if (growDown) dy else -dy).coerceIn(100, dm.heightPixels - extraHeight.toInt())
                    curX = if (growRight) x0 else x0 + (w0 - curW)
                    curY = if (growDown) y0 else y0 + (h0 - curH)
                    // keep the panel on screen
                    curX = curX.coerceIn(0, (dm.widthPixels - curW).coerceAtLeast(0))
                    curY = curY.coerceIn(0, (dm.heightPixels - extraHeight.toInt() - curH).coerceAtLeast(0))
                    if (curW == sv.mFloatingWidth && curH == sv.mFloatingHeight && curX == x0 && curY == y0)
                        return@setOnTouchListener true
                    setFloatingSize(context, curW, curH)
                    val decorLp = view.rootView.layoutParams as? WindowManager.LayoutParams
                    if (decorLp != null && (decorLp.x != curX || decorLp.y != curY || decorLp.width != curW)) {
                        decorLp.x = curX
                        decorLp.y = curY
                        decorLp.width = curW
                        windowManager(view).updateViewLayout(view.rootView, decorLp)
                    }
                    if (SystemClock.uptimeMillis() - lastReload > RESIZE_RELOAD_INTERVAL_MS) {
                        lastReload = SystemClock.uptimeMillis()
                        KeyboardSwitcher.getInstance().reloadKeyboard()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    setFloatingSize(context, curW, curH)
                    KeyboardSwitcher.getInstance().reloadKeyboard()
                    true
                }
                else -> false
            }
        }
    }
}
