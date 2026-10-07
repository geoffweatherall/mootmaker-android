package com.mootmaker.data.config

/**
 * Which mootmaker deployment the app talks to. Production is the default; any other environment
 * (test, or an ephemeral one) is chosen by name in the hidden developer setting on the About screen.
 */
data class Environment(val name: String) {
    init {
        require(NAME.matches(name)) { "Environment names are lowercase letters, digits and hyphens" }
    }

    val isProduction: Boolean get() = name == PRODUCTION_NAME

    /** The webapp's address, which also serves mobile-config.json. Mirrors the webapp's deploy.sh. */
    val siteUrl: String
        get() = if (isProduction) "https://www.mootmaker.com" else "https://www.$name.mootmaker.com"

    val configUrl: String get() = "$siteUrl/mobile-config.json"

    companion object {
        // Declared before PRODUCTION, whose constructor reads it.
        private val NAME = Regex("^[a-z0-9-]+$")
        const val PRODUCTION_NAME = "production"
        val PRODUCTION = Environment(PRODUCTION_NAME)

        /** Blank means production. Returns null if the name isn't a valid environment name. */
        fun fromInput(input: String): Environment? {
            val trimmed = input.trim().lowercase()
            if (trimmed.isEmpty()) return PRODUCTION
            return if (NAME.matches(trimmed)) Environment(trimmed) else null
        }
    }
}
