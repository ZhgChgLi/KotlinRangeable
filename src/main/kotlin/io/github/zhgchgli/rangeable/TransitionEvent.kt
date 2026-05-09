package io.github.zhgchgli.rangeable

/**
 * Kind of a boundary event.
 */
public enum class TransitionKind {
    OPEN,
    CLOSE;

    /** Lower-case JSON-style string ("open" / "close"). */
    public val tag: String get() = name.lowercase()
}

/**
 * A single boundary event in coordinate-sorted order.
 *
 * `coordinate` is normally a finite [Int]; it is `null` for close events
 * whose underlying interval ends at the implementation's +∞ sentinel
 * (RFC §4.7 C4). Comparison treats `null` as greater than any finite Int.
 */
public data class TransitionEvent<E>(
    val coordinate: Int?,
    val kind: TransitionKind,
    val element: E,
)
