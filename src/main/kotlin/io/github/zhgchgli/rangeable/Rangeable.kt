package io.github.zhgchgli.rangeable

/** Internal record per element key. */
internal data class ElementEntry<E>(
    val element: E,
    val set: DisjointSet,
    val ord: Int,
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
            _version += 1
            eventIndex = null
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
            val newSet = DisjointSet()
            for (iv in entry.set) {
                newSet.insert(iv.lo, iv.hi)
            }
            dup.byKey[key] = ElementEntry(entry.element, newSet, entry.ord)
        }
        dup._version = _version
        return dup
    }

    private fun ensureEventIndexFresh() {
        val cached = eventIndex
        if (cached != null && cached.version == _version) return
        val vStart = _version
        val rebuilt = BoundaryIndex.build(byKey, vStart, intMaxSentinel)
        if (_version == vStart) {
            eventIndex = rebuilt
        }
    }
}
