package com.mootmaker.app

import android.app.Application

class MootmakerApplication : Application() {
    private var current: AppContainer? = null

    /** Created on first use, so tests can [replaceContainer] before the first activity starts. */
    val container: AppContainer
        get() = current ?: AppContainer(this).also { current = it }

    /** For tests: swap in a container wired to a fake backend. */
    fun replaceContainer(container: AppContainer) {
        current = container
    }
}
