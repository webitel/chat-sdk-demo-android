package com.webitel.chat.sdk.demo_android.ui.dialog

import android.content.Context
import android.util.AttributeSet
import androidx.recyclerview.widget.LinearLayoutManager


class MessagesLinearLayoutManager @JvmOverloads constructor(
    context: Context?,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : LinearLayoutManager(context, attrs, defStyleAttr, defStyleRes) {
    override fun isAutoMeasureEnabled(): Boolean {
        return false
    }
}