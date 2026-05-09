package io.github.zhgchgli.rangeable

/**
 * Immutable closed integer interval `[lo, hi]`.
 *
 * Both ends are inclusive, matching RFC §4.1. `lo > hi` throws
 * [InvalidIntervalException] at construction time.
 */
public data class Interval(val lo: Int, val hi: Int) {
    init {
        if (lo > hi) throw InvalidIntervalException("lo ($lo) > hi ($hi)")
    }

    public operator fun contains(coord: Int): Boolean = coord in lo..hi

    public fun toPair(): Pair<Int, Int> = lo to hi
}
