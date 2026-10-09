package com.mootmaker.data.meeting

import java.text.Normalizer
import java.util.Locale

/**
 * Whether [name] matches what was typed in a people picker's filter (issue #27): the trimmed [query]
 * appears anywhere in the name, ignoring case and accents ("ann" finds "Joanna" and "Anne", "jose"
 * finds "José"). A blank query matches everyone. Only the name is searched, never the email.
 */
fun matchesName(name: String, query: String): Boolean {
    val wanted = fold(query.trim())
    return wanted.isEmpty() || fold(name).contains(wanted)
}

/** Lower-cased with [Locale.ROOT] and with combining accents stripped. */
private fun fold(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase(Locale.ROOT)
