package com.akustom15.liquidglasslib

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.min

/**
 * A FrameLayout that renders a real Liquid Glass (frosted glass) effect.
 *
 * How it works:
 * 1. Captures the content behind the view (including the window background)
 * 2. Applies a strong multi-pass gaussian blur
 * 3. Draws the blurred content as the view background
 * 4. Overlays a subtle glass gradient tint and thin inner-shadow edges
 */
class LiquidGlassView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /** Corner radius for the rounded glass shape. */
    var cornerRadius: Float = 50f
        set(value) { field = value; rebuildShadowBitmap(); invalidate() }

    /** Whether to draw a drop shadow behind the glass. */
    var addOuterShadow: Boolean = true
        set(value) { field = value; invalidate() }

    /** Blur strength. Higher = more blurred. Default is 80. */
    var blurRadius: Int = 100
        set(value) { field = value; invalidate() }

    /** Number of blur passes. More passes = stronger/smoother blur. */
    var blurPasses: Int = 3
        set(value) { field = value; invalidate() }

    // ── Paints ───────────────────────────────────────────────────────
    private val gradientPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    private val outerShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clipPath = Path()

    // Pre-rendered inner shadow edges (needs software canvas for BlurMaskFilter)
    private var shadowBitmap: Bitmap? = null

    // Blurred background capture
    private var blurredBitmap: Bitmap? = null
    private var isCapturing = false

    // Downscale factor: smaller = stronger apparent blur when upscaled
    private val downscale = 0.12f

    init {
        setWillNotDraw(false)
        background = null
    }

    // ── Lifecycle ────────────────────────────────────────────────────
    private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
        if (!isCapturing && visibility == View.VISIBLE && width > 0 && height > 0) {
            captureAndBlurBackground()
        }
        true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(preDrawListener)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(preDrawListener)
        blurredBitmap?.recycle(); blurredBitmap = null
        shadowBitmap?.recycle(); shadowBitmap = null
        super.onDetachedFromWindow()
    }

    // ── Background capture & blur ────────────────────────────────────
    private fun captureAndBlurBackground() {
        val parentView = parent as? ViewGroup ?: return

        val scaledW = (width * downscale).toInt()
        val scaledH = (height * downscale).toInt()
        if (scaledW <= 0 || scaledH <= 0) return

        isCapturing = true

        // Create / reuse the capture bitmap
        if (blurredBitmap == null ||
            blurredBitmap!!.width != scaledW ||
            blurredBitmap!!.height != scaledH
        ) {
            blurredBitmap?.recycle()
            blurredBitmap = Bitmap.createBitmap(scaledW, scaledH, Bitmap.Config.ARGB_8888)
        }

        val captureCanvas = Canvas(blurredBitmap!!)
        captureCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        // ── Step 1: Draw the WINDOW background ──
        // The dark background color comes from the Activity theme,
        // not the parent ConstraintLayout. We must draw it explicitly.
        val rootBg = rootView?.background
        if (rootBg != null) {
            captureCanvas.save()
            captureCanvas.scale(downscale, downscale)
            val loc = IntArray(2)
            getLocationInWindow(loc)
            captureCanvas.translate(-loc[0].toFloat(), -loc[1].toFloat())
            rootBg.setBounds(0, 0, rootView!!.width, rootView!!.height)
            rootBg.draw(captureCanvas)
            captureCanvas.restore()
        } else {
            // Fallback: solid dark
            captureCanvas.drawColor(Color.BLACK)
        }

        // ── Step 2: Draw siblings (everything the parent draws, minus this view) ──
        captureCanvas.save()
        captureCanvas.scale(downscale, downscale)
        captureCanvas.translate(-left.toFloat(), -top.toFloat())
        // parentView.draw() will call draw() on all children.
        // Our draw() override returns early because isCapturing == true,
        // so this view is excluded from the capture.
        parentView.draw(captureCanvas)
        captureCanvas.restore()

        // ── Step 3: Multi-pass StackBlur (pure Kotlin, works everywhere) ──
        val effectiveRadius = (blurRadius * downscale).toInt().coerceIn(1, 25)
        for (pass in 0 until blurPasses) {
            stackBlur(blurredBitmap!!, effectiveRadius)
        }

        isCapturing = false
    }

    // ── Size changed → rebuild clip, gradient, shadows ───────────────
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0 || h == 0) return

        val wf = w.toFloat()
        val hf = h.toFloat()
        val scale = min(wf, hf) / 900f

        clipPath.reset()
        clipPath.addRoundRect(
            RectF(0f, 0f, wf, hf), cornerRadius, cornerRadius, Path.Direction.CW
        )

        // Glass gradient: 15 % → 30 % white
        gradientPaint.shader = LinearGradient(
            0f, 0f, wf, hf,
            Color.argb(38, 255, 255, 255),
            Color.argb(77, 255, 255, 255),
            Shader.TileMode.CLAMP
        )

        // Outer shadow
        val outerBlur = 30f * scale
        outerShadowPaint.color = Color.argb(64, 0, 0, 0)
        if (outerBlur > 0f) {
            outerShadowPaint.maskFilter =
                BlurMaskFilter(outerBlur, BlurMaskFilter.Blur.NORMAL)
        }

        rebuildShadowBitmap()
    }

    /** Pre-render inner-shadow edges to a software bitmap (BlurMaskFilter). */
    private fun rebuildShadowBitmap() {
        val w = width; val h = height
        if (w == 0 || h == 0) return
        val wf = w.toFloat(); val hf = h.toFloat()
        val scale = min(wf, hf) / 900f

        shadowBitmap?.recycle()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp) // software canvas

        val clip = Path()
        clip.addRoundRect(
            RectF(0f, 0f, wf, hf), cornerRadius, cornerRadius, Path.Direction.CW
        )
        c.save()
        c.clipPath(clip)

        // Inner shadow 1 (white 60 %)
        val p1 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.argb(153, 255, 255, 255)
            strokeWidth = 3f * scale
            maskFilter = BlurMaskFilter(8f * scale, BlurMaskFilter.Blur.NORMAL)
        }
        val path1 = Path().apply {
            val ox = -2f * scale; val oy = 2f * scale
            addRoundRect(
                RectF(ox, oy, wf + ox, hf + oy),
                cornerRadius, cornerRadius, Path.Direction.CW
            )
        }
        c.drawPath(path1, p1)

        // Inner shadow 2 (white 30 %)
        val p2 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.argb(77, 255, 255, 255)
            strokeWidth = 2f * scale
            maskFilter = BlurMaskFilter(6f * scale, BlurMaskFilter.Blur.NORMAL)
        }
        val path2 = Path().apply {
            val ox = 1f * scale; val oy = -1f * scale
            addRoundRect(
                RectF(ox, oy, wf + ox, hf + oy),
                cornerRadius, cornerRadius, Path.Direction.CW
            )
        }
        c.drawPath(path2, p2)

        c.restore()
        shadowBitmap = bmp
    }

    // ── Drawing ──────────────────────────────────────────────────────
    override fun draw(canvas: Canvas) {
        if (isCapturing) return
        super.draw(canvas)
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (isCapturing) return

        val wf = width.toFloat()
        val hf = height.toFloat()
        val scale = min(wf, hf) / 900f

        // Outer shadow
        if (addOuterShadow) {
            val oy = 15f * scale
            canvas.drawRoundRect(
                RectF(0f, oy, wf, hf + oy),
                cornerRadius, cornerRadius, outerShadowPaint
            )
        }

        // Clip to rounded rect
        canvas.save()
        canvas.clipPath(clipPath)

        // 1. Blurred background
        blurredBitmap?.let {
            canvas.drawBitmap(
                it,
                Rect(0, 0, it.width, it.height),
                RectF(0f, 0f, wf, hf),
                bitmapPaint
            )
        }

        // 2. Subtle glass gradient overlay
        canvas.drawRoundRect(
            RectF(0f, 0f, wf, hf), cornerRadius, cornerRadius, gradientPaint
        )

        // 3. Inner shadow edges
        shadowBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }

        canvas.restore()

        // 4. Children
        super.dispatchDraw(canvas)
    }

    // ── StackBlur algorithm (pure Kotlin, works on ALL Android versions) ─
    companion object {
        /**
         * Stack Blur Algorithm by Mario Klingemann.
         * Applies a gaussian-like blur to a Bitmap in-place.
         */
        fun stackBlur(bitmap: Bitmap, radius: Int) {
            if (radius < 1) return

            val w = bitmap.width
            val h = bitmap.height
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

            val wm = w - 1
            val hm = h - 1
            val wh = w * h
            val div = radius + radius + 1

            val r = IntArray(wh)
            val g = IntArray(wh)
            val b = IntArray(wh)
            val a = IntArray(wh)

            var rsum: Int; var gsum: Int; var bsum: Int; var asum: Int
            var rinsum: Int; var ginsum: Int; var binsum: Int; var ainsum: Int
            var routsum: Int; var goutsum: Int; var boutsum: Int; var aoutsum: Int

            val vmin = IntArray(maxOf(w, h))

            var divsum = (div + 1) shr 1
            divsum *= divsum
            val dv = IntArray(256 * divsum)
            for (i in dv.indices) dv[i] = i / divsum

            var yi = 0
            var yw = 0

            val stack = Array(div) { IntArray(4) }

            for (y in 0 until h) {
                rinsum = 0; ginsum = 0; binsum = 0; ainsum = 0
                routsum = 0; goutsum = 0; boutsum = 0; aoutsum = 0
                rsum = 0; gsum = 0; bsum = 0; asum = 0

                for (i in -radius..radius) {
                    val p = pixels[yi + minOf(wm, maxOf(i, 0))]
                    val sir = stack[i + radius]
                    sir[0] = (p shr 16) and 0xff
                    sir[1] = (p shr 8) and 0xff
                    sir[2] = p and 0xff
                    sir[3] = (p shr 24) and 0xff
                    val rbs = radius + 1 - abs(i)
                    rsum += sir[0] * rbs
                    gsum += sir[1] * rbs
                    bsum += sir[2] * rbs
                    asum += sir[3] * rbs
                    if (i > 0) {
                        rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]; ainsum += sir[3]
                    } else {
                        routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2]; aoutsum += sir[3]
                    }
                }

                var stackpointer = radius
                for (x in 0 until w) {
                    a[yi] = dv[asum]
                    r[yi] = dv[rsum]
                    g[yi] = dv[gsum]
                    b[yi] = dv[bsum]

                    rsum -= routsum; gsum -= goutsum; bsum -= boutsum; asum -= aoutsum

                    val stackstart = stackpointer - radius + div
                    val sir = stack[stackstart % div]
                    routsum -= sir[0]; goutsum -= sir[1]; boutsum -= sir[2]; aoutsum -= sir[3]

                    if (y == 0) vmin[x] = minOf(x + radius + 1, wm)

                    val p = pixels[yw + vmin[x]]
                    sir[0] = (p shr 16) and 0xff
                    sir[1] = (p shr 8) and 0xff
                    sir[2] = p and 0xff
                    sir[3] = (p shr 24) and 0xff

                    rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]; ainsum += sir[3]
                    rsum += rinsum; gsum += ginsum; bsum += binsum; asum += ainsum

                    stackpointer = (stackpointer + 1) % div
                    val sir2 = stack[stackpointer % div]
                    routsum += sir2[0]; goutsum += sir2[1]; boutsum += sir2[2]; aoutsum += sir2[3]
                    rinsum -= sir2[0]; ginsum -= sir2[1]; binsum -= sir2[2]; ainsum -= sir2[3]

                    yi++
                }
                yw += w
            }

            for (x in 0 until w) {
                rinsum = 0; ginsum = 0; binsum = 0; ainsum = 0
                routsum = 0; goutsum = 0; boutsum = 0; aoutsum = 0
                rsum = 0; gsum = 0; bsum = 0; asum = 0

                var yp = -radius * w
                for (i in -radius..radius) {
                    yi = maxOf(0, yp) + x
                    val sir = stack[i + radius]
                    sir[0] = r[yi]; sir[1] = g[yi]; sir[2] = b[yi]; sir[3] = a[yi]
                    val rbs = radius + 1 - abs(i)
                    rsum += r[yi] * rbs; gsum += g[yi] * rbs; bsum += b[yi] * rbs; asum += a[yi] * rbs
                    if (i > 0) {
                        rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]; ainsum += sir[3]
                    } else {
                        routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2]; aoutsum += sir[3]
                    }
                    if (i < hm) yp += w
                }

                yi = x
                var stackpointer = radius
                for (y in 0 until h) {
                    pixels[yi] = (dv[asum] shl 24) or (dv[rsum] shl 16) or (dv[gsum] shl 8) or dv[bsum]

                    rsum -= routsum; gsum -= goutsum; bsum -= boutsum; asum -= aoutsum

                    val stackstart = stackpointer - radius + div
                    val sir = stack[stackstart % div]
                    routsum -= sir[0]; goutsum -= sir[1]; boutsum -= sir[2]; aoutsum -= sir[3]

                    if (x == 0) vmin[y] = minOf(y + radius + 1, hm) * w

                    val ip = x + vmin[y]
                    sir[0] = r[ip]; sir[1] = g[ip]; sir[2] = b[ip]; sir[3] = a[ip]

                    rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]; ainsum += sir[3]
                    rsum += rinsum; gsum += ginsum; bsum += binsum; asum += ainsum

                    stackpointer = (stackpointer + 1) % div
                    val sir2 = stack[stackpointer]
                    routsum += sir2[0]; goutsum += sir2[1]; boutsum += sir2[2]; aoutsum += sir2[3]
                    rinsum -= sir2[0]; ginsum -= sir2[1]; binsum -= sir2[2]; ainsum -= sir2[3]

                    yi += w
                }
            }

            bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        }
    }
}
