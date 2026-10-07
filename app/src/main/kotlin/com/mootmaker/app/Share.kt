package com.mootmaker.app

import android.content.Context
import android.content.Intent

/** Opens the system share sheet with [url], titled by [subject]. */
fun shareLink(context: Context, subject: String, url: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, url)
    }
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
