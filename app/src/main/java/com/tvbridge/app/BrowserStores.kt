package com.tvbridge.app

import android.content.Context

internal class BrowserStores(context: Context) {
    val history = BrowserHistory(context)
    val bookmarks = BrowserBookmarks(context)
    val settings = BrowserSettings(context)
}
