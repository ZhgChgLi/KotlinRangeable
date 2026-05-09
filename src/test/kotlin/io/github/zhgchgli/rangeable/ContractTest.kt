package io.github.zhgchgli.rangeable

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * RFC §10 — 23 normative contract tests.
 *
 * Mirrors `RubyRangeable/test/rangeable_test.rb` and the Swift / Python
 * / TypeScript ports. Test #20 (random property) is in
 * [PropertyTest]. Test #23.A (Int.max sentinel) uses the optional
 * `intMaxSentinel` constructor parameter via [Int.MAX_VALUE].
 */
class ContractTest {

    private data class Strong(val tag: String = "strong")
    private data class Italic(val tag: String = "italic")
    private data class Code(val tag: String = "code")
    private data class Link(val url: String)

    private fun pairs(intervals: List<Interval>): List<Pair<Int, Int>> =
        intervals.map { it.lo to it.hi }

    private fun tuples(events: List<TransitionEvent<Any>>): List<Triple<Int?, TransitionKind, Any>> =
        events.map { Triple(it.coordinate, it.kind, it.element) }

    // ---------------------------------------------------------------- //
    @Test fun `01 empty container`() {
        val r = Rangeable<Any>()
        assertEquals(emptyList(), r[0].objs)
        assertEquals(emptyList(), r.getRange(Strong()))
        assertEquals(0, r.size)
        assertTrue(r.isEmpty)
    }

    @Test fun `02 single insert`() {
        val r = Rangeable<Any>()
        r.insert(Strong(), 2, 5)
        assertEquals(listOf(Strong()), r[2].objs)
        assertEquals(listOf(Strong()), r[5].objs)
        assertEquals(emptyList(), r[6].objs)
        assertEquals(emptyList(), r[1].objs)
    }

    @Test fun `03 inclusive end`() {
        val r = Rangeable<Any>()
        r.insert(Strong(), 3, 8)
        assertEquals(listOf(Strong()), r[8].objs)
        assertEquals(emptyList(), r[9].objs)
    }

    @Test fun `04 single-point`() {
        val r = Rangeable<Any>()
        r.insert(Strong(), 4, 4)
        assertEquals(emptyList(), r[3].objs)
        assertEquals(listOf(Strong()), r[4].objs)
        assertEquals(emptyList(), r[5].objs)
    }

    @Test fun `05 same-element overlap merge`() {
        val r = Rangeable<Any>()
        r.insert(Strong(), 2, 5)
        r.insert(Strong(), 3, 7)
        assertEquals(listOf(2 to 7), pairs(r.getRange(Strong())))
    }

    @Test fun `06 same-element adjacency merge`() {
        val r = Rangeable<Any>()
        r.insert(Strong(), 2, 4)
        r.insert(Strong(), 5, 7)
        assertEquals(listOf(2 to 7), pairs(r.getRange(Strong())))
    }

    @Test fun `07 same-element non-adjacent disjoint`() {
        val r = Rangeable<Any>()
        r.insert(Strong(), 2, 4)
        r.insert(Strong(), 6, 7)
        assertEquals(listOf(2 to 4, 6 to 7), pairs(r.getRange(Strong())))
    }

    @Test fun `08 same-element nested`() {
        val r = Rangeable<Any>()
        r.insert(Strong(), 2, 10)
        r.insert(Strong(), 4, 6)
        assertEquals(listOf(2 to 10), pairs(r.getRange(Strong())))
    }

    @Test fun `09 idempotent insert`() {
        val r = Rangeable<Any>()
        r.insert(Strong(), 2, 5)
        val v1 = r.version
        r.insert(Strong(), 2, 5)
        assertEquals(listOf(2 to 5), pairs(r.getRange(Strong())))
        assertEquals(v1, r.version)
    }

    @Test fun `10 different elements coexist`() {
        val r = Rangeable<Any>()
        r.insert(Strong(), 2, 5)
        r.insert(Italic(), 3, 7)
        assertEquals(listOf<Any>(Strong(), Italic()), r[3].objs)
        assertEquals(listOf<Any>(Italic()), r[6].objs)
        assertEquals(listOf(2 to 5), pairs(r.getRange(Strong())))
        assertEquals(listOf(3 to 7), pairs(r.getRange(Italic())))
    }

    @Test fun `11 equal-by-equality elements merge`() {
        val r = Rangeable<Any>()
        r.insert(Link("a"), 2, 5)
        r.insert(Link("a"), 4, 8)
        r.insert(Link("b"), 6, 9)
        assertEquals(listOf(2 to 8), pairs(r.getRange(Link("a"))))
        assertEquals(listOf(6 to 9), pairs(r.getRange(Link("b"))))
    }

    @Test fun `12 first-insert order at point`() {
        val r = Rangeable<Any>()
        r.insert(Strong(), 1, 10)
        r.insert(Italic(), 1, 10)
        r.insert(Code(), 1, 10)
        assertEquals(listOf<Any>(Strong(), Italic(), Code()), r[5].objs)
    }

    @Test fun `13 order preserved through merge`() {
        val r = Rangeable<Any>()
        r.insert(Strong(), 1, 5)
        r.insert(Italic(), 3, 7)
        r.insert(Strong(), 4, 8)
        assertEquals(listOf<Any>(Strong(), Italic()), r[6].objs)
    }

    @Test fun `14 transitions over range`() {
        val r = Rangeable<Any>()
        r.insert(Strong(), 2, 5)
        r.insert(Italic(), 3, 7)
        assertEquals(
            listOf(
                Triple<Int?, TransitionKind, Any>(2, TransitionKind.OPEN, Strong()),
                Triple<Int?, TransitionKind, Any>(3, TransitionKind.OPEN, Italic()),
                Triple<Int?, TransitionKind, Any>(6, TransitionKind.CLOSE, Strong()),
                Triple<Int?, TransitionKind, Any>(8, TransitionKind.CLOSE, Italic()),
            ),
            tuples(r.transitions(0, 10)),
        )
    }

    @Test fun `15 transitions same-start`() {
        val r = Rangeable<Any>()
        r.insert(Strong(), 3, 5)
        r.insert(Italic(), 3, 7)
        assertEquals(
            listOf(
                Triple<Int?, TransitionKind, Any>(3, TransitionKind.OPEN, Strong()),
                Triple<Int?, TransitionKind, Any>(3, TransitionKind.OPEN, Italic()),
                Triple<Int?, TransitionKind, Any>(6, TransitionKind.CLOSE, Strong()),
                Triple<Int?, TransitionKind, Any>(8, TransitionKind.CLOSE, Italic()),
            ),
            tuples(r.transitions(0, 10)),
        )
    }

    @Test fun `16 transitions same-end LIFO close order`() {
        val r = Rangeable<Any>()
        r.insert(Strong(), 3, 5)
        r.insert(Italic(), 3, 5)
        assertEquals(
            listOf(
                Triple<Int?, TransitionKind, Any>(3, TransitionKind.OPEN, Strong()),
                Triple<Int?, TransitionKind, Any>(3, TransitionKind.OPEN, Italic()),
                Triple<Int?, TransitionKind, Any>(6, TransitionKind.CLOSE, Italic()),
                Triple<Int?, TransitionKind, Any>(6, TransitionKind.CLOSE, Strong()),
            ),
            tuples(r.transitions(0, 10)),
        )
    }

    @Test fun `17 start greater than end throws`() {
        val r = Rangeable<Any>()
        assertFailsWith<InvalidIntervalException> {
            r.insert(Strong(), 5, 2)
        }
        assertTrue(r.isEmpty)
    }

    @Test fun `18 negative start`() {
        val r = Rangeable<Any>()
        r.insert(Strong(), -2, 3)
        assertEquals(listOf(Strong()), r[-1].objs)
        assertEquals(listOf(Strong()), r[0].objs)
        assertEquals(listOf(Strong()), r[3].objs)
        assertEquals(emptyList(), r[4].objs)
    }

    @Test fun `19 insert read interleave rebuild`() {
        val r = Rangeable<Any>()
        r.insert(Strong(), 1, 3)
        val read1 = r[2].objs
        r.insert(Strong(), 5, 7)
        val read2 = r[6].objs
        assertEquals(listOf(Strong()), read1)
        assertEquals(listOf(Strong()), read2)
        assertEquals(listOf(1 to 3, 5 to 7), pairs(r.getRange(Strong())))
    }

    @Test fun `21 idempotent insert no version bump`() {
        val r = Rangeable<Any>()
        r.insert(Strong(), 2, 5)
        val v1 = r.version
        r.insert(Strong(), 2, 5)
        assertEquals(v1, r.version)
    }

    @Test fun `21A idempotent strict containment`() {
        val r = Rangeable<Any>()
        r.insert(Strong(), 2, 10)
        val v1 = r.version
        r.insert(Strong(), 4, 6)
        assertEquals(listOf(2 to 10), pairs(r.getRange(Strong())))
        assertEquals(v1, r.version)
    }

    @Test fun `22 transitions from greater than to throws`() {
        val r = Rangeable<Any>()
        r.insert(Strong(), 2, 5)
        assertFailsWith<InvalidIntervalException> {
            r.transitions(5, 2)
        }
    }

    @Test fun `23 Int_MIN as start`() {
        val intMin = Int.MIN_VALUE
        val r = Rangeable<Any>()
        r.insert(Strong(), intMin, intMin + 5)
        assertEquals(listOf(Strong()), r[intMin].objs)
        assertEquals(listOf(Strong()), r[intMin + 5].objs)
        assertEquals(emptyList(), r[intMin + 6].objs)
        assertEquals(listOf(intMin to (intMin + 5)), pairs(r.getRange(Strong())))
    }

    @Test fun `23A intMaxSentinel close coord is null`() {
        val intMax = Int.MAX_VALUE
        val r = Rangeable<Any>(intMaxSentinel = intMax)
        r.insert(Strong(), 100, intMax)
        val events = r.transitions(50, intMax)
        assertEquals(
            listOf<Pair<Int?, TransitionKind>>(
                100 to TransitionKind.OPEN,
                null to TransitionKind.CLOSE,
            ),
            events.map { it.coordinate to it.kind },
        )
    }

    // ---------------------------------------------------------------- //
    // Additional coverage: size / isEmpty / iteration / copy / chaining
    // ---------------------------------------------------------------- //

    @Test fun `size and isEmpty track distinct elements`() {
        val r = Rangeable<Any>()
        assertEquals(0, r.size)
        assertTrue(r.isEmpty)
        r.insert(Strong(), 1, 2)
        assertEquals(1, r.size)
        assertFalse(r.isEmpty)
        r.insert(Strong(), 3, 4) // same equivalence class
        assertEquals(1, r.size)
        r.insert(Italic(), 1, 2)
        assertEquals(2, r.size)
    }

    @Test fun `iteration yields pairs in first-insert order`() {
        val r = Rangeable<Any>()
        r.insert(Italic(), 3, 4)
        r.insert(Strong(), 1, 2)
        val pairs = r.toList().map { (e, ivs) -> e to ivs.map { it.lo to it.hi } }
        assertEquals(
            listOf(
                Italic() as Any to listOf(3 to 4),
                Strong() as Any to listOf(1 to 2),
            ),
            pairs,
        )
    }

    @Test fun `copy is deep and independent`() {
        val r1 = Rangeable<Any>()
        r1.insert(Strong(), 1, 5)
        val r2 = r1.copy()
        r2.insert(Strong(), 10, 12)
        assertEquals(listOf(1 to 5), pairs(r1.getRange(Strong())))
        assertEquals(listOf(1 to 5, 10 to 12), pairs(r2.getRange(Strong())))
    }

    @Test fun `insert returns this for chaining`() {
        val r = Rangeable<Any>()
        val out = r.insert(Strong(), 1, 2).insert(Italic(), 3, 4)
        assertTrue(out === r)
    }

    @Test fun `transitions to null means plus infinity`() {
        val r = Rangeable<Any>()
        r.insert(Strong(), 2, 5)
        val events = r.transitions(0, null)
        assertEquals(2, events.size)
        assertEquals(6, events[1].coordinate)
        assertEquals(TransitionKind.CLOSE, events[1].kind)
    }
}
