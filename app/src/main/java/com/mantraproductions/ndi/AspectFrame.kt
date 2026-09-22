package com.mantraproductions.ndi

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * Holds the picture at 16:9 and gives the rest back as black.
 *
 * The black is not decoration. It is where the keys live, and it is the reason
 * this camera can have thirty controls on screen without a single one of them
 * sitting on top of the shot. A phone's screen is 20:9 and a broadcast picture
 * is 16:9, so that margin exists whether or not anything is put in it.
 *
 * Measured here rather than by a constraint ratio, because the frame has to
 * answer with its own size — the rails are laid out against what is left, and
 * a ratio applied to a child after the parent has decided would leave the keys
 * either overlapping the picture or floating away from it.
 */
class AspectFrame @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : FrameLayout(context, attrs, defStyle) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = MeasureSpec.getSize(widthMeasureSpec)
        val availableHeight = MeasureSpec.getSize(heightMeasureSpec)
        if (availableWidth <= 0 || availableHeight <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }

        // Whichever way the phone is held, the picture is the largest 16:9 box
        // that fits. In landscape that is limited by the height, in portrait by
        // the width, and nothing here has to know which case it is in.
        val box = Mechanism.pictureBox(availableWidth, availableHeight)
        val width = box[0]
        val height = box[1]

        setMeasuredDimension(width, height)
        measureChildren(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        )
    }
}
