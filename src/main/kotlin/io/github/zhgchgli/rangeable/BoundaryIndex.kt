package io.github.zhgchgli.rangeable

/** Internal raw event carrying the ord tiebreaker. */
internal data class RawEvent<E>(
    val coordinate: Int?,
    val kind: TransitionKind,
    val element: E,
    val ord: Int,
)

/** One maximal run of integers over which the active set is constant. */
internal data class Segment<E>(
    val lo: Int,
    val hi: Int,
    val active: List<E>,
)

/**
 * Total order over coordinates: `null` (== +∞) is greater than any
 * finite [Int]. Returns -1 / 0 / +1.
 */
internal fun compareCoord(a: Int?, b: Int?): Int = when {
    a == null && b == null -> 0
    a == null -> 1
    b == null -> -1
    a < b -> -1
    a > b -> 1
    else -> 0
}

internal fun coordLe(coord: Int?, upper: Int?): Boolean =
    compareCoord(coord, upper) <= 0

internal fun coordGe(coord: Int?, threshold: Int?): Boolean =
    compareCoord(coord, threshold) >= 0

/**
 * Lazy boundary-event index per RFC §5.2 / §6.3. Built from a snapshot
 * of the per-element interval map plus the insertion-order map `ord`.
 */
internal class BoundaryIndex<E>(
    val events: List<TransitionEvent<E>>,
    val segments: List<Segment<E>>,
    val version: Int,
) {
    /**
     * Find the segment containing `coord`, or `null` if none.
     * O(log |segments|). `coord` must be a finite Int.
     */
    fun segmentAt(coord: Int): Segment<E>? {
        var lo = 0
        var hi = segments.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (segments[mid].hi >= coord) hi = mid else lo = mid + 1
        }
        if (lo >= segments.size) return null
        val seg = segments[lo]
        return if (seg.lo <= coord) seg else null
    }

    /**
     * Returns events whose coordinate falls in `[lo, upperCoord]`.
     * `upperCoord` may be `null` to mean +∞.
     */
    fun eventsInRange(lo: Int, upperCoord: Int?): List<TransitionEvent<E>> {
        val n = events.size
        var l = 0
        var r = n
        while (l < r) {
            val m = (l + r) ushr 1
            if (coordGe(events[m].coordinate, lo)) r = m else l = m + 1
        }
        val result = ArrayList<TransitionEvent<E>>()
        var i = l
        while (i < n && coordLe(events[i].coordinate, upperCoord)) {
            result.add(events[i])
            i += 1
        }
        return result
    }

    companion object {
        /**
         * Build a fresh index from the per-element interval map and the
         * insertion-order map. `intMaxSentinel` (default `null`) lets the
         * caller opt into "treat `hi == sentinel` as +∞" semantics for
         * cross-language fixture parity with bounded-int languages.
         */
        fun <E> build(
            byKey: LinkedHashMap<E, ElementEntry<E>>,
            snapshotVersion: Int,
            intMaxSentinel: Int? = null,
        ): BoundaryIndex<E> {
            val raw = ArrayList<RawEvent<E>>()
            for ((_, entry) in byKey) {
                val (element, set, ord) = entry
                for (iv in set) {
                    raw.add(RawEvent(iv.lo, TransitionKind.OPEN, element, ord))
                    val closeCoord: Int? =
                        if (intMaxSentinel != null && iv.hi == intMaxSentinel) null
                        else iv.hi + 1
                    raw.add(RawEvent(closeCoord, TransitionKind.CLOSE, element, ord))
                }
            }

            // Sort: coord asc (null > finite); same coord opens before
            // closes; same coord-and-kind opens by ord asc, closes by ord desc.
            raw.sortWith(Comparator { a, b ->
                val c = compareCoord(a.coordinate, b.coordinate)
                if (c != 0) return@Comparator c
                val ak = if (a.kind == TransitionKind.OPEN) 0 else 1
                val bk = if (b.kind == TransitionKind.OPEN) 0 else 1
                if (ak != bk) return@Comparator ak - bk
                if (a.kind == TransitionKind.OPEN) a.ord - b.ord else b.ord - a.ord
            })

            val publicEvents: List<TransitionEvent<E>> = raw.map {
                TransitionEvent(it.coordinate, it.kind, it.element)
            }
            val segments = materialiseSegments(raw)
            return BoundaryIndex(publicEvents, segments, snapshotVersion)
        }

        /**
         * Sweep events linearly, materialising a [Segment] for every
         * maximal run of integers over which the active set is constant.
         * Per RFC §6.3 we do not emit a segment whose active set is
         * empty.
         */
        private fun <E> materialiseSegments(events: List<RawEvent<E>>): List<Segment<E>> {
            val segments = ArrayList<Segment<E>>()
            val activeByOrd = LinkedHashMap<Int, E>()
            var prevCoord: Int? = null
            var i = 0
            val n = events.size
            while (i < n) {
                val coord = events[i].coordinate

                // Emit segment for [prevCoord, coord - 1] before
                // processing events at this coord, if the active set is
                // non-empty.
                if (prevCoord != null && activeByOrd.isNotEmpty() && coord != null) {
                    val segHi = coord - 1
                    val sortedOrds = activeByOrd.keys.sorted()
                    val snapshot = sortedOrds.map { activeByOrd.getValue(it) }
                    segments.add(Segment(prevCoord, segHi, snapshot))
                }

                // Apply every event at this coord.
                while (i < n && events[i].coordinate == coord) {
                    val ev = events[i]
                    if (ev.kind == TransitionKind.OPEN) {
                        activeByOrd[ev.ord] = ev.element
                    } else {
                        activeByOrd.remove(ev.ord)
                    }
                    i += 1
                }

                prevCoord = coord
            }
            return segments
        }
    }
}
