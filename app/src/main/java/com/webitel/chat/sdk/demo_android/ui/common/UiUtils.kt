package com.webitel.chat.sdk.demo_android.ui.common

import android.annotation.SuppressLint
import android.graphics.Color
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
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


/** "John Doe" → "JD" */
fun initials(name: String): String =
    name.split(" ")
        .take(2)
        .mapNotNull { it.firstOrNull()?.uppercase() }
        .joinToString("")


@SuppressLint("SimpleDateFormat")
fun formatTime(timeStamp: Long): String =
    SimpleDateFormat("HH:mm").format(Date(timeStamp))


@SuppressLint("SimpleDateFormat")
fun formatDateTime(timeStamp: Long): String =
    SimpleDateFormat("dd.MM HH:mm").format(Date(timeStamp))


/** Shows every message from [messages] as a Toast while the fragment is visible. */
fun Fragment.collectToasts(messages: Flow<String>) {
    viewLifecycleOwner.lifecycleScope.launch {
        viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            messages.collect { Toast.makeText(requireContext(), it, Toast.LENGTH_LONG).show() }
        }
    }
}
