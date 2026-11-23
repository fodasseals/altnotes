package com.xnotes.ui

import com.xnotes.settings.ExplorerSortKey

private fun naturalSortKey(text: String): List<Any> {
    val s = text.lowercase()
    val tokens = ArrayList<Any>()
    val sb = StringBuilder()
    var digitMode: Boolean? = null

    for (ch in s) {
        val isDigit = ch in '0'..'9'
        if (digitMode != null && digitMode != isDigit && sb.isNotEmpty()) {
            val chunk = sb.toString()
            tokens.add(if (digitMode) chunk.toBigIntegerOrNull() ?: chunk else chunk)
            sb.clear()
        }
        digitMode = isDigit
        sb.append(ch)
    }
    if (sb.isNotEmpty()) {
        val chunk = sb.toString()
        tokens.add(if (digitMode == true) chunk.toBigIntegerOrNull() ?: chunk else chunk)
    }
    return tokens
}

internal fun compareNaturalText(a: String, b: String): Int {
    val ka = naturalSortKey(a)
    val kb = naturalSortKey(b)
    val n = minOf(ka.size, kb.size)
    for (i in 0 until n) {
        val ea = ka[i]
        val eb = kb[i]
        val cmp = when {
            ea is java.math.BigInteger && eb is java.math.BigInteger -> ea.compareTo(eb)
            ea is java.math.BigInteger && eb is String -> -1
            ea is String && eb is java.math.BigInteger -> 1
            else -> (ea as String).compareTo(eb as String)
        }
        if (cmp != 0) return cmp
    }
    return ka.size.compareTo(kb.size)
}

internal fun explorerComparator(
    key: ExplorerSortKey,
    descending: Boolean,
    foldersFirst: Boolean = true,
    createdOf: (BrowseEntry) -> Long,
): Comparator<BrowseEntry> {
    val base: Comparator<BrowseEntry> = when (key) {
        ExplorerSortKey.NAME -> Comparator { a, b -> compareNaturalText(a.name, b.name) }
        ExplorerSortKey.MODIFIED -> compareBy({ it.modified }, { createdOf(it) })
        ExplorerSortKey.CREATED -> compareBy({ createdOf(it) }, { it.modified })
        ExplorerSortKey.SIZE -> compareBy({ it.size }, { createdOf(it) })
    }
    val within = (if (descending) base.reversed() else base).thenComparator { a, b -> compareNaturalText(a.name, b.name) }
    if (!foldersFirst) return within
    return Comparator { a, b ->
        when {
            a.isDir && !b.isDir -> -1
            !a.isDir && b.isDir -> 1
            else -> within.compare(a, b)
        }
    }
}
