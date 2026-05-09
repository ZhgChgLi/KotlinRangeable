package io.github.zhgchgli.rangeable

/**
 * Wraps the ordered list of elements active at a coordinate.
 *
 * `objs` is sorted by first-insertion order ascending (RFC §4.5).
 * The same coordinate within an unmutated container always returns
 * an equivalent [Slot].
 */
public data class Slot<E>(val objs: List<E>) : Iterable<E> {
    public val size: Int get() = objs.size
    public val isEmpty: Boolean get() = objs.isEmpty()

    override fun iterator(): Iterator<E> = objs.iterator()
}
