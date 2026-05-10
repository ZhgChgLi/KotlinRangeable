package io.github.zhgchgli.rangeable

/** Internal record per element key. */
internal data class ElementEntry<E>(
    val element: E,
    val set: DisjointSet,
    var ord: Int,
)

/**
 * Generic, integer-coordinate, closed-interval set container.
 *
 * Pairs hashable elements with their merged disjoint integer ranges
 * and supports three query families:
 *
 *   * by-element via [getRange]
 *   * by-position via [get] (`r[i]`)
 *   * by-range via [transitions]
 *
 * Element type `E : Any` (non-null). Equality is decided by `equals`
 * / `hashCode` per RFC §4.2 (E1–E5). Kotlin `data class` auto-generates
 * compatible implementations.
 *
 * See [RFC §3](https://github.com/ZhgChgLi/RangeableRFC) for the full
 * normative API surface.
 */
public class Rangeable<E : Any> public constructor(
    private val intMaxSentinel: Int? = null,
) : Iterable<Pair<E, List<Interval>>> {

    private val byKey: LinkedHashMap<E, ElementEntry<E>> = LinkedHashMap()
    private var _version: Int = 0
    private var eventIndex: BoundaryIndex<E>? = null

    public val version: Int get() = _version
    public val size: Int get() = byKey.size
    public val isEmpty: Boolean get() = byKey.isEmpty()

    /**
     * Insert [element] covering the closed interval `[start, end]`.
     *
     * Idempotent per RFC §3.2: re-inserting a sub-range that is already
     * fully contained leaves the container unchanged and does NOT bump
     * [version].
     *
     * @throws InvalidIntervalException if `start > end`.
     * @return `this` for chaining.
     */
    public fun insert(element: E, start: Int, end: Int): Rangeable<E> {
        if (start > end) throw InvalidIntervalException("start ($start) > end ($end)")

        val entry = byKey.getOrPut(element) {
            ElementEntry(element, DisjointSet(), byKey.size + 1)
        }
        val result = entry.set.insert(start, end)
        if (result == InsertResult.MUTATED) {
            bumpAndInvalidate()
        }
        return this
    }

    /**
     * Active-element list at coordinate [i]. RFC §3.3.
     *
     * O(log |segments| + r) once the index is built. Returns an empty
     * [Slot] for coordinates outside every segment.
     */
    public operator fun get(i: Int): Slot<E> {
        ensureEventIndexFresh()
        val seg = eventIndex!!.segmentAt(i)
        return if (seg == null) Slot(emptyList()) else Slot(seg.active)
    }

    /**
     * Merged ranges for [element] as `List<Interval>`. RFC §3.4.
     *
     * Returns an empty list when no element equal to [element] has ever
     * been inserted.
     */
    public fun getRange(element: E): List<Interval> {
        val entry = byKey[element]
        return entry?.set?.toIntervals() ?: emptyList()
    }

    /**
     * Open / close events within the inclusive coordinate range
     * `[from, to]`. RFC §3.5.
     *
     * @param to `null` means +∞ (include all events through the upper
     *   bound).
     * @throws InvalidIntervalException if `from > to`.
     */
    public fun transitions(from: Int, to: Int?): List<TransitionEvent<E>> {
        if (to != null && from > to) throw InvalidIntervalException("from ($from) > to ($to)")
        ensureEventIndexFresh()
        val upper: Int? = when {
            to == null -> null
            // RFC §4.7 C4: succ(Some(Int.max)) := None. Querying through
            // the sentinel must include the null close events.
            intMaxSentinel != null && to == intMaxSentinel -> null
            else -> to + 1
        }
        return eventIndex!!.eventsInRange(from, upper)
    }

    /** Iterate `(element, ranges)` pairs in first-insert order ascending. */
    override fun iterator(): Iterator<Pair<E, List<Interval>>> = iterator {
        for ((_, entry) in byKey) {
            yield(entry.element to entry.set.toIntervals())
        }
    }

    /**
     * Deep copy. Mutation on the copy MUST NOT affect this instance,
     * and vice versa.
     */
    public fun copy(): Rangeable<E> {
        val dup = Rangeable<E>(intMaxSentinel = intMaxSentinel)
        for ((key, entry) in byKey) {
            val newSet = DisjointSet(entry.set.toIntervals())
            dup.byKey[key] = ElementEntry(entry.element, newSet, entry.ord)
        }
        dup._version = _version
        return dup
    }

    // ====================================================================== //
    // §6.6–§6.9 Removal API (v2 normative)
    // ====================================================================== //

    /**
     * Subtract `[start, end]` from `R(element)`, per RFC §6.6.
     *
     * If `element ∉ keys(self)` (RFC §6.6 step 2), or if no entry of
     * `R(element)` overlaps `[start, end]` (step 4), this is a no-op:
     * MUST NOT bump [version], MUST NOT invalidate the boundary index
     * (RFC §4.10 (N3)).
     *
     * If the resulting `R(element)` is empty, the element is eagerly
     * pruned from `intervals`, `insertion_order`, and `ord`
     * (RFC §4.10 (N1)); subsequent elements have their `ord`
     * dense-renumbered.
     *
     * @throws InvalidIntervalException if `start > end`.
     * @return `this` for chaining.
     */
    public fun remove(element: E, start: Int, end: Int): Rangeable<E> {
        if (start > end) throw InvalidIntervalException("start ($start) > end ($end)")
        val entry = byKey[element] ?: return this // §6.6 step 2 no-op
        val result = entry.set.subtract(start, end)
        if (result is MutationResult.Mutated) {
            if (result.becameEmpty) {
                pruneElement(element)
            }
            bumpAndInvalidate()
        }
        return this
    }

    /**
     * Excise [element] entirely, per RFC §6.7.
     *
     * If `element ∉ keys(self)`, this is a no-op (no version bump,
     * no boundary-index invalidation; RFC §4.10 (N3)).
     *
     * @return `this` for chaining.
     */
    public fun remove(element: E): Rangeable<E> {
        if (!byKey.containsKey(element)) return this // §6.7 step 1 no-op
        pruneElement(element)
        bumpAndInvalidate()
        return this
    }

    /**
     * Reset the container to the empty state, per RFC §6.8.
     *
     * Idempotent on an empty container (no version bump, no boundary
     * invalidation; RFC §4.10 (N3)).
     *
     * Post-state: `isEmpty == true`, `size == 0`, every subsequent
     * `getRange(_)` returns the empty list, `[i]` returns an empty
     * [Slot], `transitions(_, _)` returns the empty list.
     *
     * @return `this` for chaining.
     */
    public fun clear(): Rangeable<E> {
        if (byKey.isEmpty()) return this // §6.8 step 1 no-op
        byKey.clear()
        bumpAndInvalidate()
        return this
    }

    /**
     * Apply `remove(_, start, end)` to every element atomically, per
     * RFC §6.9.
     *
     * One [version] bump per call (NOT one per element). If no
     * element's `R(_)` overlaps `[start, end]`, this is a no-op
     * (RFC §4.10 (N3)). Elements that become empty are eagerly pruned.
     *
     * @throws InvalidIntervalException if `start > end`.
     * @return `this` for chaining.
     */
    public fun removeRanges(start: Int, end: Int): Rangeable<E> {
        if (start > end) throw InvalidIntervalException("start ($start) > end ($end)")

        var anyChange = false
        // Snapshot keys so we can mutate byKey while iterating.
        val keys = ArrayList(byKey.keys)
        for (e in keys) {
            val entry = byKey[e] ?: continue
            val result = entry.set.subtract(start, end)
            if (result is MutationResult.Mutated) {
                anyChange = true
                if (result.becameEmpty) {
                    byKey.remove(e)
                }
            }
        }
        if (!anyChange) return this // §6.9 step 3 idempotent no-op

        // Step 4: dense renumber `ord` over the survivors. Because
        // LinkedHashMap iteration follows insertion order natively, a
        // single pass suffices.
        renumberOrd()
        bumpAndInvalidate()
        return this
    }

    // ====================================================================== //
    // §6.10–§6.13 Set operations (v2 normative)
    // ====================================================================== //

    /**
     * Per-element union with [other], returning a fresh container.
     * RFC §6.10.
     *
     * Insertion-order rule: self-primary; keys in `other ∖ self` are
     * tail-appended in `other`'s insertion order.
     */
    public fun union(other: Rangeable<E>): Rangeable<E> {
        val out = Rangeable<E>(intMaxSentinel = intMaxSentinel)
        // Step 1: walk self's insertion order.
        for ((key, selfEntry) in byKey) {
            val otherEntry = other.byKey[key]
            val merged = if (otherEntry == null) {
                selfEntry.set.toIntervals()
            } else {
                DisjointSet.mergeDisjointLists(
                    selfEntry.set.toIntervals(),
                    otherEntry.set.toIntervals(),
                )
            }
            // (I1.4) guarantees merged is non-empty when selfEntry is non-empty.
            out.appendCanonical(key, merged)
        }
        // Step 2: tail-append keys ∈ other ∖ self.
        for ((key, otherEntry) in other.byKey) {
            if (byKey.containsKey(key)) continue
            out.appendCanonical(key, otherEntry.set.toIntervals())
        }
        return out
    }

    /**
     * Per-element intersection with [other], returning a fresh
     * container. RFC §6.11.
     *
     * Elements with empty result are eagerly pruned (RFC §4.10).
     * Insertion-order rule: preserve self's order over surviving keys;
     * dense-renumber `ord`.
     */
    public fun intersect(other: Rangeable<E>): Rangeable<E> {
        val out = Rangeable<E>(intMaxSentinel = intMaxSentinel)
        for ((key, selfEntry) in byKey) {
            val otherEntry = other.byKey[key] ?: continue
            val intersected = DisjointSet.intersectDisjointLists(
                selfEntry.set.toIntervals(),
                otherEntry.set.toIntervals(),
            )
            if (intersected.isEmpty()) continue // §4.10 eager prune
            out.appendCanonical(key, intersected)
        }
        return out
    }

    /**
     * Per-element set difference (`self ∖ other`), returning a fresh
     * container. RFC §6.12.
     *
     * Elements with empty result are eagerly pruned. Insertion-order
     * rule: preserve self's order over surviving keys; dense-renumber.
     */
    public fun difference(other: Rangeable<E>): Rangeable<E> {
        val out = Rangeable<E>(intMaxSentinel = intMaxSentinel)
        for ((key, selfEntry) in byKey) {
            val otherEntry = other.byKey[key]
            val remaining = if (otherEntry == null || otherEntry.set.isEmpty) {
                selfEntry.set.toIntervals()
            } else {
                DisjointSet.subtractDisjointLists(
                    selfEntry.set.toIntervals(),
                    otherEntry.set.toIntervals(),
                )
            }
            if (remaining.isEmpty()) continue // §4.10 eager prune
            out.appendCanonical(key, remaining)
        }
        return out
    }

    /**
     * Per-element symmetric difference with [other], returning a fresh
     * container. RFC §6.13.
     *
     * Algebraic identity: `R_self(e) △ R_other(e) =
     * (R_self(e) ∖ R_other(e)) ∪ (R_other(e) ∖ R_self(e))`.
     *
     * Elements with empty result are eagerly pruned. Insertion-order
     * rule: self-primary; keys in `other ∖ self` tail-appended in
     * other's order; dense-renumber.
     */
    public fun symmetricDifference(other: Rangeable<E>): Rangeable<E> {
        val out = Rangeable<E>(intMaxSentinel = intMaxSentinel)
        // Step 1: self-primary keys.
        for ((key, selfEntry) in byKey) {
            val otherList = other.byKey[key]?.set?.toIntervals() ?: emptyList()
            val sym = DisjointSet.symmetricDifferenceDisjointLists(
                selfEntry.set.toIntervals(),
                otherList,
            )
            if (sym.isEmpty()) continue // §4.10 eager prune
            out.appendCanonical(key, sym)
        }
        // Step 2: other-only keys.
        for ((key, otherEntry) in other.byKey) {
            if (byKey.containsKey(key)) continue
            val sym = otherEntry.set.toIntervals()
            if (sym.isEmpty()) continue // unreachable per (I1.4); defensive
            out.appendCanonical(key, sym)
        }
        return out
    }

    // ---------------------------- Operator sugar ---------------------------- //

    public operator fun plus(other: Rangeable<E>): Rangeable<E> = union(other)

    public operator fun minus(other: Rangeable<E>): Rangeable<E> = difference(other)

    // ---------------------------- In-place forms ---------------------------- //

    /**
     * Mutating union: `this := this ∪ other`. RFC §6.10 in-place form.
     *
     * Bumps [version] iff the result is structurally `!= self` (any
     * keys added, any `R(e)` enlarged); idempotent dual of §3.2.
     *
     * @return `this` for chaining.
     */
    public fun unionInPlace(other: Rangeable<E>): Rangeable<E> {
        val result = union(other)
        adoptIfChanged(result)
        return this
    }

    /**
     * Mutating intersect: `this := this ∩ other`. RFC §6.11 in-place
     * form. Bumps [version] iff structurally changed.
     */
    public fun intersectInPlace(other: Rangeable<E>): Rangeable<E> {
        val result = intersect(other)
        adoptIfChanged(result)
        return this
    }

    /**
     * Mutating difference: `this := this ∖ other`. RFC §6.12 in-place
     * form. Bumps [version] iff structurally changed.
     */
    public fun differenceInPlace(other: Rangeable<E>): Rangeable<E> {
        val result = difference(other)
        adoptIfChanged(result)
        return this
    }

    /**
     * Mutating symmetric difference: `this := this △ other`. RFC §6.13
     * in-place form. Bumps [version] iff structurally changed.
     */
    public fun symmetricDifferenceInPlace(other: Rangeable<E>): Rangeable<E> {
        val result = symmetricDifference(other)
        adoptIfChanged(result)
        return this
    }

    public operator fun plusAssign(other: Rangeable<E>) {
        unionInPlace(other)
    }

    public operator fun minusAssign(other: Rangeable<E>) {
        differenceInPlace(other)
    }

    // ====================================================================== //
    // Internal helpers
    // ====================================================================== //

    private fun ensureEventIndexFresh() {
        val cached = eventIndex
        if (cached != null && cached.version == _version) return
        val vStart = _version
        val rebuilt = BoundaryIndex.build(byKey, vStart, intMaxSentinel)
        if (_version == vStart) {
            eventIndex = rebuilt
        }
    }

    private fun bumpAndInvalidate() {
        _version += 1
        eventIndex = null
    }

    /**
     * Eager-prune [element] from `byKey` and dense-renumber `ord` over
     * the survivors. RFC §4.10 (N1) + (I2.c). Caller is responsible for
     * the version bump + index invalidation.
     */
    private fun pruneElement(element: E) {
        val removed = byKey.remove(element) ?: return
        // Renumber every surviving entry whose original ord exceeded
        // the removed one. Because LinkedHashMap preserves insertion
        // order, we can simply walk and decrement the ones with
        // `ord > removed.ord`.
        for ((_, entry) in byKey) {
            if (entry.ord > removed.ord) {
                entry.ord -= 1
            }
        }
    }

    /**
     * Rebuild `ord` densely 1..N over surviving keys in their current
     * `byKey` iteration order (which matches insertion order for
     * `LinkedHashMap`). Used after [removeRanges] where multiple
     * elements may have been pruned in a single op.
     */
    private fun renumberOrd() {
        var i = 1
        for ((_, entry) in byKey) {
            entry.ord = i
            i += 1
        }
    }

    /**
     * Append [key] with already-canonical [intervals] to this container
     * during set-op construction. Bypasses `insert` to avoid re-running
     * the (I1) bsearch + merge work, since the inputs are guaranteed
     * canonical by their producers (`mergeDisjointLists`, etc.).
     *
     * Caller MUST guarantee [intervals] is non-empty and (I1)-canonical.
     */
    private fun appendCanonical(key: E, intervals: List<Interval>) {
        val newSet = DisjointSet(intervals)
        byKey[key] = ElementEntry(key, newSet, byKey.size + 1)
    }

    /**
     * Adopt the contents of [result] into `this` if structurally
     * different; bump [version] once on change. Used by all
     * `*InPlace` set-op forms.
     *
     * Per RFC §6.10/§6.11/§6.12/§6.13 mutating-form rule: the version
     * bumps iff the post-state is structurally `!= self`.
     */
    private fun adoptIfChanged(result: Rangeable<E>) {
        if (structurallyEquals(result)) return
        byKey.clear()
        for ((key, entry) in result.byKey) {
            byKey[key] = ElementEntry(entry.element, DisjointSet(entry.set.toIntervals()), entry.ord)
        }
        bumpAndInvalidate()
    }

    /**
     * Structural equality: same key set, same insertion order, same
     * per-element interval list, same `ord`. Used by the `*InPlace`
     * forms' idempotence check.
     */
    private fun structurallyEquals(other: Rangeable<E>): Boolean {
        if (byKey.size != other.byKey.size) return false
        val it1 = byKey.entries.iterator()
        val it2 = other.byKey.entries.iterator()
        while (it1.hasNext() && it2.hasNext()) {
            val e1 = it1.next()
            val e2 = it2.next()
            if (e1.key != e2.key) return false
            if (e1.value.set.toIntervals() != e2.value.set.toIntervals()) return false
        }
        return true
    }
}
