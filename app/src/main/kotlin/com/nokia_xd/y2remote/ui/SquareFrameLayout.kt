package com.nokia_xd.y2remote.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * A FrameLayout that maintains a 1:1 square aspect ratio based on its width.
 */
class SquareFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Enforce 1:1 aspect ratio by using the width measure spec for height
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
    }
}
