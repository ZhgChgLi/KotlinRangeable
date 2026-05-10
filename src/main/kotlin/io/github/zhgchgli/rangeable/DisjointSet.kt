package io.github.zhgchgli.rangeable

/**
 * Outcome of [DisjointSet.insert]. The owning [Rangeable] bumps its
 * version counter only on [MUTATED]; [IDEMPOTENT] means the insert was
 * absorbed and the canonical state is unchanged (RFC Test #21,
 * Lemma 6.5.B).
 */
internal enum class InsertResult { MUTATED, IDEMPOTENT }

/**
 * Outcome of any v2 mutation that may shrink the set
 * (`subtract(lo, hi)`, `subtractList`, `intersectList`,
 * `symmetricDifferenceList`, `unionList`). The owning [Rangeable] uses
 * this to drive the version-bump rule (RFC §4.10 (N3)) and eager
 * pruning (RFC §4.10 (N1)).
 */
internal sealed class MutationResult {
    /**
     * The set is unchanged; caller MUST NOT bump `version` and MUST NOT
     * invalidate `event_index`.
     */
    object Unchanged : MutationResult()

    /**
     * The set actually changed. [becameEmpty] `true` means the set is
     * now empty and the parent element MUST be eagerly pruned.
     */
    data class Mutated(val becameEmpty: Boolean) : MutationResult()
}

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
    private val entries: MutableList<Interval>

    /** Default constructor — empty set. */
    constructor() {
        this.entries = ArrayList()
    }

    /**
     * Internal constructor used by set-op constructions to bypass the
     * `insert` path. The caller MUST guarantee `canonicalEntries` is
     * already (I1)-canonical (sorted, disjoint, non-adjacent).
     */
    constructor(canonicalEntries: List<Interval>) {
        this.entries = ArrayList(canonicalEntries)
    }

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

    /**
     * Subtract `[lo, hi]` from the set, per RFC §6.6 step 5.
     *
     * Each consumed entry produces 0..2 residual entries depending on
     * whether the cut leaves a left residual, a right residual, both,
     * or neither.
     *
     * Underflow / overflow safety (RFC §6.6 Underflow safety, dual of
     * §6.1 (P5)): `start - 1` is computed only when `iv.lo < start`,
     * so `start > Int.MIN_VALUE`; `end + 1` is computed only when
     * `end < iv.hi`, so `end < Int.MAX_VALUE`.
     */
    fun subtract(lo: Int, hi: Int): MutationResult {
        if (lo > hi) throw InvalidIntervalException("lo ($lo) > hi ($hi)")

        // Step 3: bsearch leftmost entry that may overlap, predicate
        // `iv.hi >= lo` (strict overlap; adjacency does NOT subtract).
        val i0 = bsearchFirstOverlap(lo)

        // Step 4: quick-exit no-op.
        if (i0 == entries.size || entries[i0].lo > hi) {
            return MutationResult.Unchanged
        }

        // Step 5: sweep all overlapping entries.
        var i = i0
        val replacements = ArrayList<Interval>(2)
        while (i < entries.size && entries[i].lo <= hi) {
            val iv = entries[i]
            // Left residual exists only when iv.lo < lo (so lo - 1 ≥ iv.lo).
            if (iv.lo < lo) {
                replacements.add(Interval(iv.lo, lo - 1))
            }
            // Right residual exists only when hi < iv.hi (so hi + 1 ≤ iv.hi).
            if (hi < iv.hi) {
                replacements.add(Interval(hi + 1, iv.hi))
            }
            i += 1
        }
        val toReplaceEnd = i

        // Step 6: splice — clear consumed, insert residuals at i0.
        entries.subList(i0, toReplaceEnd).clear()
        if (replacements.isNotEmpty()) {
            entries.addAll(i0, replacements)
        }
        return MutationResult.Mutated(becameEmpty = entries.isEmpty())
    }

    /**
     * Replace the underlying entries with [newEntries], asserting the
     * caller has already canonicalised. Used by set-op in-place forms
     * to avoid re-running the (I1) checks. Returns [MutationResult] for
     * the version-bump decision.
     */
    fun replaceEntries(newEntries: List<Interval>): MutationResult {
        if (entries == newEntries) {
            return MutationResult.Unchanged
        }
        // Compare element-by-element to detect structural equality even
        // across different list instances.
        if (entries.size == newEntries.size) {
            var same = true
            for (k in entries.indices) {
                if (entries[k] != newEntries[k]) { same = false; break }
            }
            if (same) return MutationResult.Unchanged
        }
        entries.clear()
        entries.addAll(newEntries)
        return MutationResult.Mutated(becameEmpty = entries.isEmpty())
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

    /** Find leftmost index `i` where `entries[i].hi >= lo` (strict overlap). */
    private fun bsearchFirstOverlap(lo: Int): Int {
        var l = 0
        var r = entries.size
        while (l < r) {
            val m = (l + r) ushr 1
            if (entries[m].hi >= lo) r = m else l = m + 1
        }
        return l
    }

    /**
     * Pure helpers shared by set-op implementations. All inputs MUST be
     * (I1)-canonical lists; outputs are (I1)-canonical.
     */
    companion object {
        /**
         * Two-pointer linear merge with adjacency-collapse, RFC §6.10
         * `merge_disjoint_lists`. Both inputs (I1)-canonical; output
         * (I1)-canonical.
         */
        fun mergeDisjointLists(a: List<Interval>, b: List<Interval>): List<Interval> {
            val out = ArrayList<Interval>(a.size + b.size)
            var i = 0
            var j = 0
            while (i < a.size && j < b.size) {
                if (a[i].lo <= b[j].lo) {
                    appendOrMerge(out, a[i]); i += 1
                } else {
                    appendOrMerge(out, b[j]); j += 1
                }
            }
            while (i < a.size) { appendOrMerge(out, a[i]); i += 1 }
            while (j < b.size) { appendOrMerge(out, b[j]); j += 1 }
            return out
        }

        /**
         * Two-pointer pairwise intersect, RFC §6.11
         * `intersect_disjoint_lists`. Output is (I1)-canonical without
         * an explicit adjacency-collapse step (Lemma 6.11.A).
         */
        fun intersectDisjointLists(a: List<Interval>, b: List<Interval>): List<Interval> {
            val out = ArrayList<Interval>()
            var i = 0
            var j = 0
            while (i < a.size && j < b.size) {
                val lo = maxOf(a[i].lo, b[j].lo)
                val hi = minOf(a[i].hi, b[j].hi)
                if (lo <= hi) {
                    out.add(Interval(lo, hi))
                }
                if (a[i].hi <= b[j].hi) i += 1 else j += 1
            }
            return out
        }

        /**
         * Two-pointer set subtraction, RFC §6.12
         * `subtract_disjoint_lists`. Output is (I1)-canonical.
         *
         * Underflow / overflow safety mirrors §6.6: `b[j].lo - 1` is
         * computed only when `b[j].lo > currentLo` (so `b[j].lo > Int.MIN_VALUE`);
         * `b[j].hi + 1` only when `b[j].hi < currentHi` (so `b[j].hi < Int.MAX_VALUE`).
         */
        fun subtractDisjointLists(a: List<Interval>, b: List<Interval>): List<Interval> {
            val out = ArrayList<Interval>()
            var i = 0
            var j = 0
            var currentLo: Int? = null
            var currentHi: Int? = null
            while (i < a.size) {
                if (currentLo == null) {
                    currentLo = a[i].lo
                    currentHi = a[i].hi
                }
                // Skip b entries strictly before current.
                while (j < b.size && b[j].hi < currentLo!!) {
                    j += 1
                }
                if (j == b.size || b[j].lo > currentHi!!) {
                    // No more cuts on this entry; commit and advance i.
                    out.add(Interval(currentLo!!, currentHi!!))
                    i += 1
                    currentLo = null
                    currentHi = null
                    continue
                }
                // b[j] overlaps [currentLo, currentHi]; cut.
                if (b[j].lo > currentLo!!) {
                    out.add(Interval(currentLo!!, b[j].lo - 1))
                }
                if (b[j].hi < currentHi!!) {
                    currentLo = b[j].hi + 1
                    j += 1
                } else {
                    // b[j] swallows the rest of the current entry.
                    i += 1
                    currentLo = null
                    currentHi = null
                }
            }
            return out
        }

        /**
         * Per-element symmetric difference. Implementation of RFC §6.13
         * via the algebraic identity `(a ∖ b) ∪ (b ∖ a)`. The final
         * `merge_disjoint_lists` is required because the two one-sided
         * residuals can be integer-adjacent (RFC §6.13 worked example,
         * Test #34 / §9 case 34).
         */
        fun symmetricDifferenceDisjointLists(
            a: List<Interval>,
            b: List<Interval>,
        ): List<Interval> {
            val left = subtractDisjointLists(a, b)
            val right = subtractDisjointLists(b, a)
            return mergeDisjointLists(left, right)
        }

        private fun appendOrMerge(out: MutableList<Interval>, iv: Interval) {
            if (out.isEmpty() || out.last().hi + 1 < iv.lo) {
                out.add(iv)
            } else {
                val last = out.last()
                if (iv.hi > last.hi) {
                    out[out.size - 1] = Interval(last.lo, iv.hi)
                }
            }
        }
    }
}
