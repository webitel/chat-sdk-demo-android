package com.webitel.chat.sdk.demo_android.repo

import com.webitel.chat.sdk.HistoryCursor


/**
 * The loaded part of a dialog's history. The cache holds one continuous window of messages:
 * usually the newest ones, or the ones around a message after a jump (search result).
 */
data class HistoryWindow(
    /** SDK: `HistorySlice.olderCursor` — null when the beginning of the dialog is loaded. */
    val olderCursor: HistoryCursor? = null,
    /** SDK: `HistorySlice.newerCursor` — null when the window reaches the newest message. */
    val newerCursor: HistoryCursor? = null
) {
    /**
     * Realtime messages are appended only when the window ends with the newest message,
     * otherwise there would be a gap between the window and the new message.
     */
    val isLatest: Boolean get() = newerCursor == null
    val hasOlder: Boolean get() = olderCursor != null
}
