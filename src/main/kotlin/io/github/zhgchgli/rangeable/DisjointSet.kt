package io.github.zhgchgli.rangeable

/**
 * Outcome of [DisjointSet.insert]. The owning [Rangeable] bumps its
 * version counter only on [MUTATED]; [IDEMPOTENT] means the insert was
 * absorbed and the canonical state is unchanged (RFC Test #21,
 * Lemma 6.5.B).
 */
internal enum class InsertResult { MUTATED, IDEMPOTENT }

/**
 * Sorted, disjoint, non-adjacent merged-interval list for one element.
 *
 * Maintains the RFC §5.1 (I1) invariant:
 *
 *   * sorted by `lo` strictly ascending
 *   * any two adjacent entries `(lo1, hi1), (lo2, hi2)` satisfy
 *     `hi1 + 1 < lo2` (no overlap, no integer adjacency)
 *   * `lo <= hi` for every entry
 */
internal class DisjointSet : Iterable<Interval> {
    private val entries: MutableList<Interval> = ArrayList()

    val size: Int get() = entries.size
    val isEmpty: Boolean get() = entries.isEmpty()

    override fun iterator(): Iterator<Interval> = entries.iterator()

    fun toIntervals(): List<Interval> = entries.toList()

    fun toPairs(): List<Pair<Int, Int>> = entries.map { it.lo to it.hi }

    /**
     * Insert `[lo, hi]` into the set, performing union-with-merge per
     * RFC §6.1.
     *
     * @return [InsertResult.MUTATED] if the canonical state changed
     *   (caller should bump version), [InsertResult.IDEMPOTENT] if the
     *   insert was absorbed by an existing entry (caller MUST NOT bump
     *   version, per Test #21 and Lemma 6.5.B).
     */
    fun insert(lo: Int, hi: Int): InsertResult {
        if (lo > hi) throw InvalidIntervalException("lo ($lo) > hi ($hi)")

        // Step 4 of §6.1: bsearch for the leftmost touch candidate.
        // Predicate: `iv.hi + 1 >= lo`. We use `iv.hi + 1` (not `lo - 1`)
        // to mirror the Ruby/Swift form for cross-language byte parity.
        val i0 = bsearchFirstTouch(lo)

        // Step 5: collect contiguous touch entries while
        // `entries[i].lo <= hi + 1`.
        var toMergeEnd = i0
        val n = entries.size
        while (toMergeEnd < n && entries[toMergeEnd].lo <= hi + 1) {
            toMergeEnd += 1
        }
        val mergeCount = toMergeEnd - i0

        // Step 6: containment idempotent fast-path. If we touch exactly
        // one existing entry that fully covers [lo, hi], this insert is
        // a no-op. MUST NOT mutate, MUST NOT bump version.
        if (mergeCount == 1) {
            val existing = entries[i0]
            if (existing.lo <= lo && hi <= existing.hi) {
                return InsertResult.IDEMPOTENT
            }
        }

        // Step 7: real mutation path. Compute merged bounds, splice in.
        var newLo = lo
        var newHi = hi
        if (mergeCount > 0) {
            val first = entries[i0]
            val last = entries[toMergeEnd - 1]
            if (first.lo < newLo) newLo = first.lo
            if (last.hi > newHi) newHi = last.hi
        }
        val merged = Interval(newLo, newHi)
        // Remove [i0, toMergeEnd) then add merged at i0. Use subList +
        // clear to avoid an allocation per removed element.
        if (mergeCount > 0) {
            entries.subList(i0, toMergeEnd).clear()
        }
        entries.add(i0, merged)
        return InsertResult.MUTATED
    }

    /** Find leftmost index `i` where `entries[i].hi + 1 >= lo`. */
    private fun bsearchFirstTouch(lo: Int): Int {
        var l = 0
        var r = entries.size
        while (l < r) {
            val m = (l + r) ushr 1
            if (entries[m].hi + 1 >= lo) r = m else l = m + 1
        }
        return l
    }
}
