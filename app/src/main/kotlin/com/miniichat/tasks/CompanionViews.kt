package com.miniichat.tasks

import android.content.Context
import android.graphics.Color
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.*

/** Native overlay uses the same neutral surfaces and blue accent as Compose. */
internal class CompanionViews(val context: Context, dark: Boolean) {
    val ink = Color.parseColor(if (dark) "#F2F1F7" else "#17191F")
    val muted = Color.parseColor(if (dark) "#B3B7C2" else "#626875")
    val surface = Color.parseColor(if (dark) "#1C1C1E" else "#FFFFFF")
    val inset = Color.parseColor(if (dark) "#29292D" else "#EFF1F5")
    val accent = Color.parseColor(if (dark) "#A6C8FF" else "#315FBB")
    fun dp(n: Int) = (n * context.resources.displayMetrics.density).toInt()
    fun shape(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    fun row() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    fun label(text: String, size: Int = 14, secondary: Boolean = false) = TextView(context).apply {
        this.text = text; textSize = size.toFloat(); setTextColor(if (secondary) muted else ink)
        setLineSpacing(dp(3).toFloat(), 1f); includeFontPadding = false
    }
    fun action(text: String, primary: Boolean = false, click: () -> Unit) = TextView(context).apply {
        this.text = text; textSize = 13f; gravity = Gravity.CENTER; minHeight = dp(38)
        setTextColor(if (primary) Color.WHITE else ink)
        background = RippleDrawable(ColorStateList.valueOf(0x22000000), shape(if (primary) Color.parseColor("#315FBB") else inset, 10), null)
        setPadding(dp(10), dp(6), dp(10), dp(6)); isClickable = true; isFocusable = true
        filterTouchesWhenObscured = true; setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) }
    }
    fun avatar(path: String, size: Int, name: String = "陪"): ImageView = object : ImageView(context) {
        override fun onDraw(canvas: android.graphics.Canvas) {
            val save = canvas.save()
            val circle = android.graphics.Path().apply { addCircle(width / 2f, height / 2f, minOf(width, height) / 2f, android.graphics.Path.Direction.CW) }
            canvas.clipPath(circle); super.onDraw(canvas); canvas.restoreToCount(save)
        }
    }.apply {
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        scaleType = ImageView.ScaleType.CENTER_CROP
        val image = if (path.isBlank()) null else runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            val options = BitmapFactory.Options().apply { inSampleSize = 1 }
            while (maxOf(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 256) options.inSampleSize *= 2
            BitmapFactory.decodeFile(path, options)
        }.getOrNull()
        if (image == null) {
            val tile = android.graphics.Bitmap.createBitmap(dp(size), dp(size), android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(tile); canvas.drawColor(inset)
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = accent; textSize = dp(size / 2).toFloat(); textAlign = android.graphics.Paint.Align.CENTER }
            canvas.drawText(name.take(1).ifBlank { "·" }, tile.width / 2f, tile.height / 2f - (paint.ascent() + paint.descent()) / 2f, paint)
            setImageBitmap(tile)
        } else setImageBitmap(image)
        clipToOutline = true; background = shape(inset, size / 2)
    }
    fun icon(symbol: String, description: String, click: () -> Unit) = TextView(context).apply {
        text = symbol; textSize = 21f; gravity = Gravity.CENTER; setTextColor(muted)
        layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
        contentDescription = description; isFocusable = true; setOnClickListener { click() }
        background = RippleDrawable(ColorStateList.valueOf(0x22000000), shape(Color.TRANSPARENT, 19), null)
        // Window replacement can remove View's deferred PerformClick callback. Commit a completed
        // tap synchronously, through performClick so accessibility still gets the standard event.
        var startX=0f;var startY=0f;var tap=false
        val slop=ViewConfiguration.get(context).scaledTouchSlop
        setOnTouchListener { target,event ->
            when(event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {startX=event.x;startY=event.y;tap=true;target.isPressed=true}
                MotionEvent.ACTION_MOVE -> if(kotlin.math.abs(event.x-startX)>slop || kotlin.math.abs(event.y-startY)>slop){tap=false;target.isPressed=false}
                MotionEvent.ACTION_UP -> {target.isPressed=false;if(tap && event.x>=0 && event.x<target.width && event.y>=0 && event.y<target.height)target.performClick();tap=false}
                MotionEvent.ACTION_CANCEL -> {tap=false;target.isPressed=false}
            }
            true
        }
    }
    fun header(name: String, state: String, path: String, collapse: () -> Unit, close: () -> Unit, drag: (View) -> Unit): LinearLayout = row().apply {
        contentDescription = "拖动窗口标题栏"; drag(this)
        val image = avatar(path, 40, name); image.contentDescription = "$name 的头像，拖动移动"; drag(image); addView(image)
        addView(column().apply {
            contentDescription = "拖动窗口标题"; drag(this)
            addView(label(name, 16).apply { setTypeface(typeface, Typeface.BOLD); maxLines = 1 })
            addView(label(state, 12, true).apply { setPadding(0, dp(5), 0, 0) })
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
        addView(icon("−", "收起陪伴", collapse).apply { tag = "collapse" }); addView(icon("×", "关闭陪伴", close))
    }
    fun scroll(content: View, maxHeight: Int): ScrollView = object : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST))
        }
    }.apply { addView(content); isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
}
