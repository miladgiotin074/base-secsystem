package com.base.realtime.watch

import android.content.Context

internal object WatchedPackages {
    private const val PREFS = "watch_packages"
    private const val KEY = "list"

    fun replace(context: Context, packages: List<String>) {
        val clean = packages.map { it.trim() }.filter { it.contains('.') }.distinct()
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, clean.joinToString("\n"))
            .apply()
    }

    fun contains(context: Context, pkg: String): Boolean = load(context).contains(pkg)

    private fun load(context: Context): Set<String> {
        val raw = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "")
            .orEmpty()
        if (raw.isBlank()) return emptySet()
        return raw.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }
}
