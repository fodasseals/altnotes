package com.xnotes.platform

import com.xnotes.core.util.DocKeys
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * What the explorer shows about each note without opening it (its page count and whether it annotates a
 * PDF), keyed by document identity like [CreationTimeStore]. Each entry carries the file's modified time
 * when it was read, so a file changed since (by this app or a sync) is read again. Writes to disk are
 * batched: a folder of new notes fills in one tile at a time, and each would otherwise rewrite the file.
 */
class DocMetaStore(private val store: JsonStore) {

    class Meta(val pages: Int, val pdf: Boolean, val modified: Long, val lastPage: Int = -1)

    private val metas: HashMap<String, Meta> = load()
    private val writer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "doc-meta").apply { isDaemon = true } }
    private var scheduled = false

    private fun load(): HashMap<String, Meta> {
        val out = HashMap<String, Meta>()
        val o = store.read()
        for (key in o.keys()) {
            val m = o.optJSONObject(key) ?: continue
            out[key] = Meta(m.optInt("pages", 0), m.optBoolean("pdf", false), m.optLong("modified", 0L), m.optInt("lastPage", -1))
        }
        return out
    }

    /** The meta read from [key] while it was last modified at [modified], or null when it has changed since. */
    @Synchronized
    fun get(key: String, modified: Long): Meta? = metas[key]?.takeIf { it.modified == modified }

    /** The meta last read from [key], however old. */
    @Synchronized
    fun latest(key: String): Meta? = metas[key]

    @Synchronized
    fun put(key: String, meta: Meta) {
        val old = metas[key]
        val keepLast = if (meta.lastPage >= 0) meta.lastPage else (old?.lastPage ?: -1)
        val merged = Meta(meta.pages, meta.pdf, meta.modified, keepLast)
        if (old != null && old.pages == merged.pages && old.pdf == merged.pdf && old.modified == merged.modified && old.lastPage == merged.lastPage) return
        metas[key] = merged
        writeSoon()
    }

    @Synchronized
    fun updateLastPage(key: String, lastPage: Int, pages: Int, pdf: Boolean) {
        val old = metas[key]
        val newPages = if (pages > 0) pages else (old?.pages ?: 0)
        val newPdf = pdf || (old?.pdf == true)
        val mod = old?.modified ?: 0L
        if (old != null && old.lastPage == lastPage && old.pages == newPages && old.pdf == newPdf) return
        metas[key] = Meta(newPages, newPdf, mod, lastPage)
        writeSoon()
    }

    @Synchronized
    fun folderDoneCount(folderKey: String): Int {
        var done = 0
        val probe = "$folderKey/__probe__"
        for ((k, m) in metas) {
            if (k != folderKey && DocKeys.moved(k, folderKey, probe) != null) {
                if (m.pages > 0 && m.lastPage >= 0 && (m.lastPage + 1) >= m.pages) done++
            }
        }
        return done
    }


    /** Carry entries across a rename or move, a folder's descendants included. */
    @Synchronized
    fun rekeyTree(from: String, to: String) {
        val moves = metas.keys.mapNotNull { k -> DocKeys.moved(k, from, to)?.let { k to it } }
        if (moves.isEmpty()) return
        val values = moves.map { (old, _) -> metas.remove(old) }
        moves.forEachIndexed { i, (_, new) -> values[i]?.let { metas[new] = it } }
        writeSoon()
    }

    @Synchronized
    fun removeMatching(predicate: (String) -> Boolean) {
        if (metas.keys.removeAll(predicate)) writeSoon()
    }

    @Synchronized
    fun clear() {
        metas.clear()
        writeSoon()
    }

    // Called with the lock held. A change after a write took its snapshot schedules the next write.
    private fun writeSoon() {
        if (scheduled) return
        scheduled = true
        writer.schedule({ store.write(takeSnapshot()) }, WRITE_DELAY_MS, TimeUnit.MILLISECONDS)
    }

    @Synchronized
    private fun takeSnapshot(): JSONObject {
        scheduled = false
        val o = JSONObject()
        for ((k, m) in metas) o.put(k, JSONObject().put("pages", m.pages).put("pdf", m.pdf).put("modified", m.modified).put("lastPage", m.lastPage))
        return o
    }

    private companion object {
        const val WRITE_DELAY_MS = 1500L
    }
}
