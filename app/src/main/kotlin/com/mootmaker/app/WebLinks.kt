package com.mootmaker.app

import android.content.Context
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
    fun calendar(personId: String) = "$siteUrl/persons/$personId/calendar" // M3
}

/** Opens a web page in a Custom Tab: an in-app browser tab with its own (separate) web session. */
fun openInCustomTab(context: Context, url: String) {
    CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, Uri.parse(url))
}
