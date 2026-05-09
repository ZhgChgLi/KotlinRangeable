package io.github.zhgchgli.rangeable

/**
 * Base class for Rangeable errors.
 */
public open class RangeableException(message: String) : RuntimeException(message)

/**
 * Thrown when an interval is malformed (`start > end`), or a transitions
 * query range is malformed (`from > to`). RFC §3.7 / §3.2 / §3.5.
 */
public class InvalidIntervalException(message: String) : RangeableException(message)
