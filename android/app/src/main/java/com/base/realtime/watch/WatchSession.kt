package com.base.realtime.watch

internal class WatchSession(
    val pkg: String,
    val label: String,
    val startedAt: Long,
) {
    val pages = ArrayList<WatchPage>()
    var lastSignature: String = ""

    fun addPage(lines: List<String>, tree: List<String>, signature: String) {
        if (lines.isEmpty() || signature.isEmpty() || signature == lastSignature) return
        if (pages.size >= 24) return
        lastSignature = signature
        pages.add(WatchPage(System.currentTimeMillis(), lines, tree, ArrayList()))
    }

    fun attachFields(fields: List<CapturedField>) {
        if (fields.isEmpty()) return
        if (pages.isEmpty()) {
            pages.add(WatchPage(System.currentTimeMillis(), emptyList(), emptyList(), ArrayList()))
        }
        pages.last().fields.clear()
        pages.last().fields.addAll(fields)
    }

    data class WatchPage(
        val at: Long,
        val lines: List<String>,
        val tree: List<String>,
        val fields: MutableList<CapturedField>,
    )
}
