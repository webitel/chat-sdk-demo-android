package com.webitel.chat.sdk.demo_android.ui.common

import android.annotation.SuppressLint
import android.graphics.Color
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.absoluteValue


fun colorFromId(id: String): Int {
    val hash = id.hashCode()
    val hue = (hash % 360).absoluteValue.toFloat()
    return Color.HSVToColor(floatArrayOf(hue, 0.5f, 0.85f))
}


fun isColorDark(color: Int): Boolean {
    val darkness =
        1 - (0.299 * Color.red(color) +
                0.587 * Color.green(color) +
                0.114 * Color.blue(color)) / 255
    return darkness >= 0.5
}


@SuppressLint("SimpleDateFormat")
fun formatTime(timeStamp: Long): String {
    val sdf = SimpleDateFormat("HH:mm")
    return try {

        val netDate = Date(timeStamp)
        sdf.format(netDate)
    } catch (e: Exception) {
        e.toString()
    }
}