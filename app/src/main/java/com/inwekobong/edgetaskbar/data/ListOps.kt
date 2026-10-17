package com.inwekobong.edgetaskbar.data

/** Pure list logic for pinned apps and recents, kept free of Android types so it is unit-tested. */
object ListOps {

    fun decode(raw: String?): List<String> =
        raw?.split('\n')?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct() ?: emptyList()

    fun encode(list: List<String>): String = list.distinct().joinToString("\n")

    /** Newest first, no duplicates (so no duplicate consecutive entries), bounded by [limit]. */
    fun addRecent(current: List<String>, pkg: String, limit: Int = 12): List<String> {
        if (current.firstOrNull() == pkg) return current.take(limit)
        return (listOf(pkg) + current.filter { it != pkg }).take(limit)
    }

    /** Appends [pkg] unless it is already pinned (no duplicate pins). */
    fun pin(current: List<String>, pkg: String): List<String> =
        if (pkg in current) current else current + pkg

    fun unpin(current: List<String>, pkg: String): List<String> = current.filter { it != pkg }

    /** Inserts [pkg] at [index] (clamped); used to undo an unpin in its old position. */
    fun insert(current: List<String>, index: Int, pkg: String): List<String> {
        if (pkg in current) return current
        val at = index.coerceIn(0, current.size)
        return current.take(at) + pkg + current.drop(at)
    }

    /** Moves the item at [from] to [to]; out-of-range requests return the list unchanged. */
    fun move(list: List<String>, from: Int, to: Int): List<String> {
        if (from !in list.indices || to !in list.indices || from == to) return list
        val m = list.toMutableList()
        val item = m.removeAt(from)
        m.add(to, item)
        return m
    }

    /** Keeps only entries that are still valid, preserving order. */
    fun keepValid(list: List<String>, valid: Set<String>): List<String> = list.filter { it in valid }

    /** Case-insensitive "contains" search by label; blank query returns everything. */
    fun <T> filterByLabel(items: List<T>, query: String, label: (T) -> String): List<T> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return items
        return items.filter { label(it).lowercase().contains(q) }
    }

    /** Splits [total] items into pages of at most [pageSize]; always at least one page. */
    fun pageCount(total: Int, pageSize: Int): Int {
        if (pageSize <= 0) return 1
        return maxOf(1, (total + pageSize - 1) / pageSize)
    }
}
