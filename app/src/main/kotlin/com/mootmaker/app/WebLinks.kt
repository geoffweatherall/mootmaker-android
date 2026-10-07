package com.mootmaker.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent

/**
 * Pages of the webapp that the app links to for features it doesn't have yet (design choice 10).
 * Each link is deleted when the milestone that builds the feature lands.
 */
class WebLinks(private val siteUrl: String) {
    fun signUp() = "$siteUrl/signup" // M8
    fun forgotPassword() = "$siteUrl/forgot-password" // M8
    fun addMeeting() = "$siteUrl/meetings/add" // M4
}

/** Opens a web page in a Custom Tab: an in-app browser tab with its own (separate) web session. */
fun openInCustomTab(context: Context, url: String) {
    CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, Uri.parse(url))
}

/** Opens the system share sheet with [url], titled by [subject]. */
fun shareLink(context: Context, subject: String, url: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, url)
    }
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
