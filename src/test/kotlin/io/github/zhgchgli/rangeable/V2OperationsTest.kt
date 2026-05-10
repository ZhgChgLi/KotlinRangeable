package io.github.zhgchgli.rangeable

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * RFC §10.B–§10.G — v2 normative removal & set-op tests.
 *
 * Mirrors the Ruby/Swift/Python/JS/Go counterparts in test-id ordering;
 * test names are prefixed `B##`, `C##`, `D##`, `E##`, `F##`, `G##` to
 * disambiguate from §10.A which already used `#21–#28` for v1 boundary
 * tests (per RFC §10.B preamble).
 *
 * Plus a handful of language-specific probes covering the
 * Kotlin-only surface: operator `+`, `-`, `+=`, `-=`, `InPlace`
 * idempotence, and `intMaxSentinel` propagation through non-mutating
 * set ops.
 */
class V2OperationsTest {

    private data class Strong(val tag: String = "strong")
    private data class Italic(val tag: String = "italic")
    private data class Code(val tag: String = "code")

    /** Single-letter element used by §10.G stress fixtures. */
    private data class Letter(val name: String)

    private val A = Letter("A")
    private val B = Letter("B")
    private val C = Letter("C")
    private val D = Letter("D")
    private val E = Letter("E")

    private fun pairs(intervals: List<Interval>): List<Pair<Int, Int>> =
        intervals.map { it.lo to it.hi }

    /** Insertion order = LinkedHashMap iteration order. */
    private fun <T : Any> insertionOrder(r: Rangeable<T>): List<T> =
        r.toList().map { it.first }

    /** Map element → its `ord` (1-based position in insertion order). */
    private fun <T : Any> ordOf(r: Rangeable<T>): Map<T, Int> =
        insertionOrder(r).withIndex().associate { (i, e) -> e to i + 1 }

    // ====================================================================== //
    // §10.B Removal Tests (#21–#43, prefixed B to avoid collision with §10.A)
    // ====================================================================== //

    @Test fun `B21 remove subrange no overlap is no-op`() {
        val r = Rangeable<Strong>()
        r.insert(Strong(), 10, 20)
        val v0 = r.version
        r.remove(Strong(), 0, 5)
        assertEquals(listOf(10 to 20), pairs(r.getRange(Strong())))
        assertEquals(v0, r.version)
        assertEquals(1, r.size)
    }

    @Test fun `B22 remove subrange exact match consumes one entry`() {
        val r = Rangeable<Strong>()
        r.insert(Strong(), 10, 20)
        r.remove(Strong(), 10, 20)
        assertEquals(0, r.size)
        assertTrue(r.isEmpty)
    }

    @Test fun `B23 remove subrange leaves left residual only`() {
        val r = Rangeable<Strong>()
        r.insert(Strong(), 0, 10)
        r.remove(Strong(), 5, 100)
        assertEquals(listOf(0 to 4), pairs(r.getRange(Strong())))
    }

    @Test fun `B24 remove subrange leaves right residual only`() {
        val r = Rangeable<Strong>()
        r.insert(Strong(), 0, 10)
        r.remove(Strong(), -100, 5)
        assertEquals(listOf(6 to 10), pairs(r.getRange(Strong())))
    }

    @Test fun `B25 remove subrange splits one entry into two`() {
        val r = Rangeable<Strong>()
        r.insert(Strong(), 0, 10)
        r.remove(Strong(), 3, 6)
        assertEquals(listOf(0 to 2, 7 to 10), pairs(r.getRange(Strong())))
    }

    @Test fun `B26 remove subrange spans multiple entries`() {
        val r = Rangeable<Strong>()
        r.insert(Strong(), 0, 5)
        r.insert(Strong(), 10, 15)
        r.insert(Strong(), 20, 25)
        r.remove(Strong(), 3, 22)
        assertEquals(listOf(0 to 2, 23 to 25), pairs(r.getRange(Strong())))
    }

    @Test fun `B27 remove subrange spans entire R(e) prunes element and renumbers`() {
        val r = Rangeable<Any>()
        r.insert(Strong(), 0, 5)
        r.insert(Strong(), 10, 15)
        r.insert(Italic(), 7, 8)
        val v0 = r.version
        r.remove(Strong(), -100, 100)
        assertEquals(1, r.size)
        assertEquals(listOf<Any>(Italic()), insertionOrder(r))
        assertEquals(1, ordOf(r)[Italic()])
        assertEquals(v0 + 1, r.version)
    }

    @Test fun `B28 remove subrange no-op MUST NOT bump version`() {
        val r = Rangeable<Any>()
        r.insert(Strong(), 10, 20)
        val v0 = r.version
        r.remove(Strong(), 30, 40) // no overlap
        val v1 = r.version
        r.remove(Italic(), 0, 5) // never inserted
        val v2 = r.version
        assertEquals(v0, v1)
        assertEquals(v0, v2)
    }

    @Test fun `B29 remove subrange with start greater than end raises`() {
        val r = Rangeable<Strong>()
        r.insert(Strong(), 0, 10)
        assertFailsWith<InvalidIntervalException> {
            r.remove(Strong(), 7, 3)
        }
        // State unchanged.
        assertEquals(listOf(0 to 10), pairs(r.getRange(Strong())))
    }

    @Test fun `B30 remove subrange with Int_MIN start is underflow safe`() {
        val intMin = Int.MIN_VALUE
        val r = Rangeable<Strong>()
        r.insert(Strong(), intMin, intMin + 100)
        r.remove(Strong(), intMin, intMin + 50)
        assertEquals(listOf((intMin + 51) to (intMin + 100)), pairs(r.getRange(Strong())))
    }

    @Test fun `B31 remove subrange with Int_MAX end is overflow safe`() {
        val intMax = Int.MAX_VALUE
        val r = Rangeable<Strong>()
        r.insert(Strong(), 0, intMax)
        r.remove(Strong(), 1000, intMax)
        assertEquals(listOf(0 to 999), pairs(r.getRange(Strong())))
    }

    @Test fun `B32 remove element excises and renumbers ord`() {
        val r = Rangeable<Any>()
        r.insert(Strong(), 0, 5)
        r.insert(Italic(), 7, 12)
        r.insert(Code(), 15, 20)
        val v0 = r.version
        r.remove(Italic())
        assertEquals(2, r.size)
        assertEquals(listOf<Any>(Strong(), Code()), insertionOrder(r))
        val ord = ordOf(r)
        assertEquals(1, ord[Strong()])
        assertEquals(2, ord[Code()]) // renumbered from 3
        assertEquals(v0 + 1, r.version)
    }

    @Test fun `B33 remove never-inserted element MUST NOT bump version`() {
        val r = Rangeable<Any>()
        r.insert(Strong(), 0, 5)
        val v0 = r.version
        r.remove(Italic())
        assertEquals(1, r.size)
        assertEquals(v0, r.version)
    }

    @Test fun `B34 remove element with single interval`() {
        val r = Rangeable<Strong>()
        r.insert(Strong(), 5, 10)
        r.remove(Strong())
        assertTrue(r.isEmpty)
        assertEquals(0, r.size)
    }

    @Test fun `B35 remove element with many intervals`() {
        val r = Rangeable<Strong>()
        r.insert(Strong(), 0, 5)
        r.insert(Strong(), 10, 15)
        r.insert(Strong(), 20, 25)
        r.remove(Strong())
        assertTrue(r.isEmpty)
        assertEquals(emptyList(), r.getRange(Strong()))
    }

    @Test fun `B36 clear on non-empty resets state and bumps version`() {
        val r = Rangeable<Any>()
        r.insert(Strong(), 0, 5)
        r.insert(Italic(), 7, 12)
        val v0 = r.version
        r.clear()
        assertTrue(r.isEmpty)
        assertEquals(0, r.size)
        assertEquals(emptyList(), r.getRange(Strong()))
        assertEquals(emptyList(), r.getRange(Italic()))
        assertEquals(v0 + 1, r.version)
    }

    @Test fun `B37 clear on empty MUST NOT bump version`() {
        val r = Rangeable<Strong>()
        val v0 = r.version
        r.clear()
        assertTrue(r.isEmpty)
        assertEquals(v0, r.version)
    }

    @Test fun `B38 post-clear queries return empty`() {
        val r = Rangeable<Strong>()
        r.insert(Strong(), 0, 5)
        r.clear()
        assertTrue(r.isEmpty)
        assertEquals(emptyList(), r[3].objs)
        assertEquals(emptyList(), r.transitions(0, 10))
    }

    @Test fun `B39 post-clear iteration is empty`() {
        val r = Rangeable<Any>()
        r.insert(Strong(), 0, 5)
        r.insert(Italic(), 7, 12)
        r.clear()
        assertEquals(0, r.size)
        assertEquals(emptyList(), r.toList())
    }

    @Test fun `B40 insert after clear assigns ord 1 (RFC Test 35-equivalent)`() {
        val r = Rangeable<Any>()
        r.insert(Strong(), 0, 5)
        r.insert(Italic(), 7, 12)
        r.clear()
        r.insert(Code(), 100, 110)
        assertEquals(1, r.size)
        assertEquals(1, ordOf(r)[Code()])
    }

    @Test fun `B41 removeRanges hits multiple elements with single bump`() {
        val r = Rangeable<Any>()
        r.insert(Strong(), 0, 10)
        r.insert(Italic(), 5, 15)
        r.insert(Code(), 100, 110)
        val v0 = r.version
        r.removeRanges(3, 8)
        assertEquals(listOf(0 to 2, 9 to 10), pairs(r.getRange(Strong())))
        assertEquals(listOf(9 to 15), pairs(r.getRange(Italic())))
        assertEquals(listOf(100 to 110), pairs(r.getRange(Code())))
        assertEquals(v0 + 1, r.version) // single bump, NOT three
    }

    @Test fun `B42 removeRanges no overlap MUST NOT bump version`() {
        val r = Rangeable<Any>()
        r.insert(Strong(), 0, 10)
        r.insert(Italic(), 50, 60)
        val v0 = r.version
        r.removeRanges(20, 30)
        assertEquals(listOf(0 to 10), pairs(r.getRange(Strong())))
        assertEquals(listOf(50 to 60), pairs(r.getRange(Italic())))
        assertEquals(v0, r.version)
    }

    @Test fun `B43 removeRanges fully covers some elements (mixed prune retain)`() {
        val r = Rangeable<Any>()
        r.insert(Strong(), 0, 5)
        r.insert(Italic(), 10, 20)
        r.insert(Code(), 25, 30)
        val v0 = r.version
        r.removeRanges(8, 22) // fully consumes Italic, leaves Strong & Code
        assertEquals(listOf(0 to 5), pairs(r.getRange(Strong())))
        assertEquals(emptyList(), r.getRange(Italic()))
        assertEquals(listOf(25 to 30), pairs(r.getRange(Code())))
        assertEquals(2, r.size)
        val ord = ordOf(r)
        assertEquals(1, ord[Strong()])
        assertEquals(2, ord[Code()]) // renumbered from 3
        assertEquals(v0 + 1, r.version)
    }

    @Test fun `B43b removeRanges covering everything empties container`() {
        val r = Rangeable<Any>()
        r.insert(Strong(), 0, 5)
        r.insert(Italic(), 10, 20)
        r.insert(Code(), 25, 30)
        val v0 = r.version
        r.removeRanges(0, 30)
        assertTrue(r.isEmpty)
        assertEquals(0, r.size)
        assertEquals(v0 + 1, r.version)
    }

    @Test fun `B43c removeRanges with start greater than end raises`() {
        val r = Rangeable<Strong>()
        r.insert(Strong(), 0, 10)
        assertFailsWith<InvalidIntervalException> {
            r.removeRanges(20, 5)
        }
        assertEquals(listOf(0 to 10), pairs(r.getRange(Strong())))
    }

    @Test fun `B43d removal methods invalidate boundary index`() {
        // Force the lazy index to build, then mutate via remove and
        // verify the next query reflects the new state (the BoundaryIndex
        // must be rebuilt because version was bumped + cleared).
        val r = Rangeable<Strong>()
        r.insert(Strong(), 0, 10)
        // Force build.
        assertEquals(listOf(Strong()), r[5].objs)
        r.remove(Strong(), 5, 5)
        // After remove the active set at coord 5 should be empty.
        assertEquals(emptyList(), r[5].objs)
        // And the residuals should be correctly visible.
        assertEquals(listOf(Strong()), r[4].objs)
        assertEquals(listOf(Strong()), r[6].objs)
    }

    // ====================================================================== //
    // §10.C Union Tests (#44–#50)
    // ====================================================================== //

    @Test fun `C44 union with disjoint elements`() {
        val r1 = Rangeable<Any>(); r1.insert(Strong(), 0, 5)
        val r2 = Rangeable<Any>(); r2.insert(Italic(), 10, 15)
        val v1Before = r1.version
        val v2Before = r2.version
        val r3 = r1.union(r2)
        assertEquals(2, r3.size)
        assertEquals(listOf<Any>(Strong(), Italic()), insertionOrder(r3))
        assertEquals(listOf(0 to 5), pairs(r3.getRange(Strong())))
        assertEquals(listOf(10 to 15), pairs(r3.getRange(Italic())))
        assertEquals(0, r3.version) // fresh container
        assertEquals(v1Before, r1.version) // sources unchanged
        assertEquals(v2Before, r2.version)
    }

    @Test fun `C45 union with same element overlapping intervals`() {
        val r1 = Rangeable<Strong>(); r1.insert(Strong(), 0, 10)
        val r2 = Rangeable<Strong>(); r2.insert(Strong(), 5, 15)
        val r3 = r1.union(r2)
        assertEquals(listOf(0 to 15), pairs(r3.getRange(Strong())))
        assertEquals(1, r3.size)
    }

    @Test fun `C46 union adjacency-merge collapses integer-adjacent`() {
        val r1 = Rangeable<Strong>(); r1.insert(Strong(), 0, 5)
        val r2 = Rangeable<Strong>(); r2.insert(Strong(), 6, 10)
        val r3 = r1.union(r2)
        assertEquals(listOf(0 to 10), pairs(r3.getRange(Strong())))
    }

    @Test fun `C47 unionInPlace with idempotent subset MUST NOT bump version`() {
        val r1 = Rangeable<Any>()
        r1.insert(Strong(), 0, 10)
        r1.insert(Italic(), 20, 30)
        val r2 = Rangeable<Any>(); r2.insert(Strong(), 3, 7)
        val v0 = r1.version
        r1.unionInPlace(r2)
        assertEquals(listOf(0 to 10), pairs(r1.getRange(Strong())))
        assertEquals(listOf(20 to 30), pairs(r1.getRange(Italic())))
        assertEquals(v0, r1.version)
    }

    @Test fun `C48 union of two empties is empty`() {
        val r1 = Rangeable<Strong>()
        val r2 = Rangeable<Strong>()
        val r3 = r1.union(r2)
        assertTrue(r3.isEmpty)
        assertEquals(0, r3.size)
        assertEquals(0, r3.version)
    }

    @Test fun `C49 union with self is structurally equal and source unchanged`() {
        val r1 = Rangeable<Any>()
        r1.insert(Strong(), 0, 5)
        r1.insert(Italic(), 10, 15)
        val v0 = r1.version
        val r2 = r1.union(r1)
        assertEquals(listOf<Any>(Strong(), Italic()), insertionOrder(r2))
        assertEquals(listOf(0 to 5), pairs(r2.getRange(Strong())))
        assertEquals(listOf(10 to 15), pairs(r2.getRange(Italic())))
        assertEquals(0, r2.version)
        assertEquals(v0, r1.version)
        // Mutating variant: no version bump.
        r1.unionInPlace(r1)
        assertEquals(v0, r1.version)
    }

    @Test fun `C50 union insertion-order tail-append rule`() {
        val r1 = Rangeable<Letter>()
        r1.insert(A, 0, 1); r1.insert(B, 2, 3)
        val r2 = Rangeable<Letter>()
        r2.insert(C, 4, 5); r2.insert(B, 10, 11); r2.insert(D, 12, 13)
        val r3 = r1.union(r2)
        assertEquals(listOf(A, B, C, D), insertionOrder(r3))
        val ord = ordOf(r3)
        assertEquals(1, ord[A]); assertEquals(2, ord[B])
        assertEquals(3, ord[C]); assertEquals(4, ord[D])
    }

    // ====================================================================== //
    // §10.D Intersect Tests (#51–#57)
    // ====================================================================== //

    @Test fun `D51 intersect with no shared elements is empty`() {
        val r1 = Rangeable<Any>(); r1.insert(Strong(), 0, 10)
        val r2 = Rangeable<Any>(); r2.insert(Italic(), 5, 15)
        val r3 = r1.intersect(r2)
        assertTrue(r3.isEmpty)
        assertEquals(0, r3.size)
    }

    @Test fun `D52 intersect with shared elements overlapping intervals`() {
        val r1 = Rangeable<Strong>(); r1.insert(Strong(), 0, 10)
        val r2 = Rangeable<Strong>(); r2.insert(Strong(), 5, 15)
        val r3 = r1.intersect(r2)
        assertEquals(listOf(5 to 10), pairs(r3.getRange(Strong())))
        assertEquals(1, r3.size)
    }

    @Test fun `D53 intersect with shared elements disjoint intervals prunes element`() {
        val r1 = Rangeable<Strong>(); r1.insert(Strong(), 0, 5)
        val r2 = Rangeable<Strong>(); r2.insert(Strong(), 100, 200)
        val r3 = r1.intersect(r2)
        assertTrue(r3.isEmpty)
        assertEquals(emptyList(), r3.getRange(Strong()))
    }

    @Test fun `D54 intersect with self is structurally equal`() {
        val r1 = Rangeable<Any>()
        r1.insert(Strong(), 0, 5)
        r1.insert(Italic(), 10, 15)
        val v0 = r1.version
        val r2 = r1.intersect(r1)
        assertEquals(listOf<Any>(Strong(), Italic()), insertionOrder(r2))
        assertEquals(listOf(0 to 5), pairs(r2.getRange(Strong())))
        assertEquals(listOf(10 to 15), pairs(r2.getRange(Italic())))
        assertEquals(0, r2.version)
        assertEquals(v0, r1.version)
    }

    @Test fun `D55 intersect with empty is empty`() {
        val r1 = Rangeable<Any>()
        r1.insert(Strong(), 0, 5)
        r1.insert(Italic(), 10, 15)
        val r2 = Rangeable<Any>()
        val r3 = r1.intersect(r2)
        assertTrue(r3.isEmpty)
    }

    @Test fun `D56 intersect produces multiple sub-intervals per element`() {
        val r1 = Rangeable<Strong>()
        r1.insert(Strong(), 0, 5)
        r1.insert(Strong(), 10, 15)
        r1.insert(Strong(), 20, 25)
        val r2 = Rangeable<Strong>(); r2.insert(Strong(), 3, 22)
        val r3 = r1.intersect(r2)
        assertEquals(listOf(3 to 5, 10 to 15, 20 to 22), pairs(r3.getRange(Strong())))
    }

    @Test fun `D57 intersect insertion-order preservation and dense ord renumber`() {
        val r1 = Rangeable<Letter>()
        r1.insert(A, 0, 5); r1.insert(B, 10, 15); r1.insert(C, 20, 25); r1.insert(D, 30, 35)
        val r2 = Rangeable<Letter>()
        r2.insert(A, 0, 5); r2.insert(C, 21, 24); r2.insert(E, 100, 200)
        val r3 = r1.intersect(r2)
        assertEquals(listOf(A, C), insertionOrder(r3))
        val ord = ordOf(r3)
        assertEquals(1, ord[A])
        assertEquals(2, ord[C]) // densely renumbered, NOT 3 from r1's original
        assertEquals(listOf(0 to 5), pairs(r3.getRange(A)))
        assertEquals(listOf(21 to 24), pairs(r3.getRange(C)))
    }

    // ====================================================================== //
    // §10.E Difference Tests (#58–#65)
    // ====================================================================== //

    @Test fun `E58 difference with disjoint elements returns self structurally`() {
        val r1 = Rangeable<Any>(); r1.insert(Strong(), 0, 10)
        val r2 = Rangeable<Any>(); r2.insert(Italic(), 5, 15)
        val r3 = r1.difference(r2)
        assertEquals(listOf<Any>(Strong()), insertionOrder(r3))
        assertEquals(listOf(0 to 10), pairs(r3.getRange(Strong())))
        assertEquals(0, r3.version)
    }

    @Test fun `E59 difference with self is empty`() {
        val r1 = Rangeable<Any>()
        r1.insert(Strong(), 0, 10)
        r1.insert(Italic(), 20, 30)
        val r2 = r1.difference(r1)
        assertTrue(r2.isEmpty)
        assertEquals(0, r2.size)
    }

    @Test fun `E60 difference creates left residuals`() {
        val r1 = Rangeable<Strong>(); r1.insert(Strong(), 0, 10)
        val r2 = Rangeable<Strong>(); r2.insert(Strong(), 5, 100)
        val r3 = r1.difference(r2)
        assertEquals(listOf(0 to 4), pairs(r3.getRange(Strong())))
    }

    @Test fun `E61 difference creates right residuals`() {
        val r1 = Rangeable<Strong>(); r1.insert(Strong(), 0, 10)
        val r2 = Rangeable<Strong>(); r2.insert(Strong(), -100, 5)
        val r3 = r1.difference(r2)
        assertEquals(listOf(6 to 10), pairs(r3.getRange(Strong())))
    }

    @Test fun `E62 difference creates both residuals (split)`() {
        val r1 = Rangeable<Strong>(); r1.insert(Strong(), 0, 10)
        val r2 = Rangeable<Strong>(); r2.insert(Strong(), 3, 6)
        val r3 = r1.difference(r2)
        assertEquals(listOf(0 to 2, 7 to 10), pairs(r3.getRange(Strong())))
    }

    @Test fun `E63 difference spans multiple L_a entries`() {
        val r1 = Rangeable<Strong>()
        r1.insert(Strong(), 0, 5)
        r1.insert(Strong(), 10, 15)
        r1.insert(Strong(), 20, 25)
        val r2 = Rangeable<Strong>(); r2.insert(Strong(), 3, 22)
        val r3 = r1.difference(r2)
        assertEquals(listOf(0 to 2, 23 to 25), pairs(r3.getRange(Strong())))
    }

    @Test fun `E64 difference insertion-order preservation`() {
        val r1 = Rangeable<Letter>()
        r1.insert(A, 0, 5); r1.insert(B, 10, 15); r1.insert(C, 20, 25); r1.insert(D, 30, 35)
        val r2 = Rangeable<Letter>()
        r2.insert(B, 9, 16); r2.insert(E, 100, 200)
        val r3 = r1.difference(r2)
        assertEquals(listOf(A, C, D), insertionOrder(r3))
        val ord = ordOf(r3)
        assertEquals(1, ord[A]); assertEquals(2, ord[C]); assertEquals(3, ord[D])
    }

    @Test fun `E65 difference equivalent to removeRanges-loop (cross-validation)`() {
        // Fixture chosen so the per-key intervals in r2 do NOT incidentally
        // overlap *other* keys in r1: r2.Strong=(3,6) only touches r1.Strong,
        // r2.Italic=(52,55) only touches r1.Italic. Otherwise the
        // §6.12 informative equivalence breaks down (`removeRanges`
        // is cross-key, `difference` is per-key).
        val r1 = Rangeable<Any>()
        r1.insert(Strong(), 0, 10)
        r1.insert(Italic(), 50, 60)
        val r2 = Rangeable<Any>()
        r2.insert(Strong(), 3, 6)
        r2.insert(Italic(), 52, 55)
        val r3 = r1.difference(r2)
        // r4 simulates the removal-set equivalence: flatten r2's intervals
        // and apply removeRanges in their natural insertion order.
        val r4 = r1.copy()
        for ((_, ivs) in r2) {
            for (iv in ivs) {
                r4.removeRanges(iv.lo, iv.hi)
            }
        }
        assertEquals(insertionOrder(r3), insertionOrder(r4))
        assertEquals(pairs(r3.getRange(Strong())), pairs(r4.getRange(Strong())))
        assertEquals(pairs(r3.getRange(Italic())), pairs(r4.getRange(Italic())))
        // Note: r3.version == 0 (fresh); r4.version > r1.version (multiple bumps).
        // Sanity check the values themselves.
        assertEquals(listOf(0 to 2, 7 to 10), pairs(r3.getRange(Strong())))
        assertEquals(listOf(50 to 51, 56 to 60), pairs(r3.getRange(Italic())))
    }

    // ====================================================================== //
    // §10.F Symmetric Difference Tests (#66–#71)
    // ====================================================================== //

    @Test fun `F66 symmetricDifference with empty is self structurally`() {
        val r1 = Rangeable<Any>()
        r1.insert(Strong(), 0, 5)
        r1.insert(Italic(), 10, 15)
        val r2 = Rangeable<Any>()
        val r3 = r1.symmetricDifference(r2)
        assertEquals(listOf<Any>(Strong(), Italic()), insertionOrder(r3))
        assertEquals(listOf(0 to 5), pairs(r3.getRange(Strong())))
        assertEquals(listOf(10 to 15), pairs(r3.getRange(Italic())))
        assertEquals(0, r3.version)
    }

    @Test fun `F67 symmetricDifference with self is empty`() {
        val r1 = Rangeable<Any>()
        r1.insert(Strong(), 0, 5)
        r1.insert(Italic(), 10, 15)
        val r2 = r1.symmetricDifference(r1)
        assertTrue(r2.isEmpty)
        assertEquals(0, r2.size)
    }

    @Test fun `F68 symmetricDifference per-element residuals from both sides`() {
        val r1 = Rangeable<Strong>(); r1.insert(Strong(), 0, 10)
        val r2 = Rangeable<Strong>(); r2.insert(Strong(), 5, 15)
        val r3 = r1.symmetricDifference(r2)
        assertEquals(listOf(0 to 4, 11 to 15), pairs(r3.getRange(Strong())))
    }

    @Test fun `F68b symmetricDifference adjacency-collapse (RFC §6_13 worked example)`() {
        // Tests #34 in §9 — the case where merge_disjoint_lists IS required
        // (sorted concat would violate I1.2).
        val r1 = Rangeable<Strong>(); r1.insert(Strong(), 0, 5)
        val r2 = Rangeable<Strong>(); r2.insert(Strong(), 6, 10)
        val r3 = r1.symmetricDifference(r2)
        assertEquals(listOf(0 to 10), pairs(r3.getRange(Strong())))
    }

    @Test fun `F69 symmetricDifference per-element commutativity (insertion order self-primary)`() {
        val r1 = Rangeable<Letter>()
        r1.insert(A, 0, 5); r1.insert(B, 10, 15)
        val r2 = Rangeable<Letter>()
        r2.insert(B, 12, 17); r2.insert(C, 20, 25)
        val r3 = r1.symmetricDifference(r2)
        val r4 = r2.symmetricDifference(r1)
        // Per-element R(e) identical for every common key.
        for (key in setOf(A, B, C)) {
            assertEquals(
                pairs(r3.getRange(key)),
                pairs(r4.getRange(key)),
            )
        }
        assertEquals(listOf(0 to 5), pairs(r3.getRange(A)))
        assertEquals(listOf(10 to 11, 16 to 17), pairs(r3.getRange(B)))
        assertEquals(listOf(20 to 25), pairs(r3.getRange(C)))
        // insertion_order is NOT commutative — by design (self-primary).
        assertEquals(listOf(A, B, C), insertionOrder(r3))
        assertEquals(listOf(B, C, A), insertionOrder(r4))
    }

    @Test fun `F70 symmetricDifference associativity (RFC Test 70 fixture)`() {
        val r1 = Rangeable<Letter>(); r1.insert(A, 0, 10)
        val r2 = Rangeable<Letter>(); r2.insert(A, 5, 15)
        val r3 = Rangeable<Letter>(); r3.insert(A, 10, 20)
        val rLeft = r1.symmetricDifference(r2).symmetricDifference(r3)
        val rRight = r1.symmetricDifference(r2.symmetricDifference(r3))
        // Both algebras yield R(A) = [(0, 4), (10, 10), (16, 20)] per RFC.
        val expected = listOf(0 to 4, 10 to 10, 16 to 20)
        assertEquals(expected, pairs(rLeft.getRange(A)))
        assertEquals(expected, pairs(rRight.getRange(A)))
        assertEquals(listOf(A), insertionOrder(rLeft))
        assertEquals(listOf(A), insertionOrder(rRight))
        assertEquals(1, ordOf(rLeft)[A])
        assertEquals(1, ordOf(rRight)[A])
    }

    @Test fun `F71 symmetricDifference insertion-order tail-append for keys in other minus self`() {
        val r1 = Rangeable<Letter>()
        r1.insert(A, 0, 5); r1.insert(B, 10, 15)
        val r2 = Rangeable<Letter>()
        r2.insert(C, 20, 25); r2.insert(D, 30, 35)
        val r3 = r1.symmetricDifference(r2)
        assertEquals(listOf(A, B, C, D), insertionOrder(r3))
        val ord = ordOf(r3)
        assertEquals(1, ord[A]); assertEquals(2, ord[B])
        assertEquals(3, ord[C]); assertEquals(4, ord[D])
    }

    // ====================================================================== //
    // §10.G Set-op Insertion-order Stress Tests (#72–#80)
    // ====================================================================== //

    @Test fun `G72 dense ord renumber after multi-element prune`() {
        val r1 = Rangeable<Letter>()
        r1.insert(A, 0, 1); r1.insert(B, 2, 3); r1.insert(C, 4, 5)
        r1.insert(D, 6, 7); r1.insert(E, 8, 9)
        val r2 = Rangeable<Letter>()
        r2.insert(B, 100, 200); r2.insert(D, 100, 200)
        val r3 = r1.intersect(r2)
        assertTrue(r3.isEmpty)
        assertEquals(0, r3.size)
    }

    @Test fun `G73 union then intersect chain preserves insertion order`() {
        val r1 = Rangeable<Letter>()
        r1.insert(A, 0, 5); r1.insert(B, 10, 15)
        val r2 = Rangeable<Letter>()
        r2.insert(C, 20, 25); r2.insert(B, 12, 17)
        val r3 = Rangeable<Letter>()
        r3.insert(B, 0, 100); r3.insert(C, 0, 100)
        val rUnion = r1.union(r2)
        assertEquals(listOf(A, B, C), insertionOrder(rUnion))
        val rChain = rUnion.intersect(r3)
        assertEquals(listOf(B, C), insertionOrder(rChain)) // A dropped
        val ord = ordOf(rChain)
        assertEquals(1, ord[B]); assertEquals(2, ord[C])
    }

    @Test fun `G74 set-op result ord is correct even if input had pruned elements`() {
        val r1 = Rangeable<Letter>()
        r1.insert(A, 0, 5); r1.insert(B, 10, 15); r1.insert(C, 20, 25)
        r1.remove(B) // r1.insertion_order == [A, C], r1.ord(C) == 2
        val r2 = r1.union(Rangeable<Letter>())
        assertEquals(listOf(A, C), insertionOrder(r2))
        val ord = ordOf(r2)
        assertEquals(1, ord[A]); assertEquals(2, ord[C])
    }

    @Test fun `G75 difference then union recovers insertion order correctly`() {
        val r1 = Rangeable<Letter>()
        r1.insert(A, 0, 10); r1.insert(B, 20, 30); r1.insert(C, 40, 50)
        val r2 = Rangeable<Letter>(); r2.insert(B, 0, 100) // fully consumes B
        val r3 = r1.difference(r2).union(r1)
        assertEquals(listOf(A, C, B), insertionOrder(r3))
    }

    @Test fun `G76 union of three with overlapping keys`() {
        val r1 = Rangeable<Letter>()
        r1.insert(A, 0, 5); r1.insert(B, 10, 15)
        val r2 = Rangeable<Letter>()
        r2.insert(B, 20, 25); r2.insert(C, 30, 35)
        val r3 = Rangeable<Letter>()
        r3.insert(C, 40, 45); r3.insert(D, 50, 55)
        val rChain = r1.union(r2).union(r3)
        assertEquals(listOf(A, B, C, D), insertionOrder(rChain))
        val ord = ordOf(rChain)
        assertEquals(1, ord[A]); assertEquals(2, ord[B])
        assertEquals(3, ord[C]); assertEquals(4, ord[D])
        assertEquals(listOf(0 to 5), pairs(rChain.getRange(A)))
        assertEquals(listOf(10 to 15, 20 to 25), pairs(rChain.getRange(B)))
        assertEquals(listOf(30 to 35, 40 to 45), pairs(rChain.getRange(C)))
        assertEquals(listOf(50 to 55), pairs(rChain.getRange(D)))
    }

    @Test fun `G77 symmetricDifference dual algebraic-form equivalence`() {
        val r1 = Rangeable<Letter>()
        r1.insert(A, 0, 10); r1.insert(B, 20, 30)
        val r2 = Rangeable<Letter>()
        r2.insert(A, 5, 15); r2.insert(C, 40, 50)
        val rForm1 = r1.symmetricDifference(r2)
        val rForm2 = r1.union(r2).difference(r1.intersect(r2))
        // Per-element R(e) byte-identical for both forms.
        for (key in setOf(A, B, C)) {
            assertEquals(
                pairs(rForm1.getRange(key)),
                pairs(rForm2.getRange(key)),
            )
        }
    }

    @Test fun `G78 insert-after-remove ord reassignment (R14 deliberate side effect)`() {
        val r = Rangeable<Letter>()
        r.insert(A, 0, 5); r.insert(B, 10, 15)
        // Sanity: ord(A) == 1, ord(B) == 2.
        val ordBefore = ordOf(r)
        assertEquals(1, ordBefore[A]); assertEquals(2, ordBefore[B])
        r.remove(A)
        // Now insertion_order == [B], ord(B) == 1.
        assertEquals(listOf(B), insertionOrder(r))
        assertEquals(1, ordOf(r)[B])
        r.insert(A, 100, 110)
        assertEquals(listOf(B, A), insertionOrder(r))
        val ordAfter = ordOf(r)
        assertEquals(1, ordAfter[B]); assertEquals(2, ordAfter[A]) // A is fresh tail
    }

    @Test fun `G79 cross-op ord consistency (intersect after union)`() {
        val r1 = Rangeable<Letter>()
        r1.insert(A, 0, 5); r1.insert(B, 10, 15); r1.insert(C, 20, 25)
        val r2 = Rangeable<Letter>()
        r2.insert(B, 12, 17); r2.insert(D, 30, 35)
        val rUnion = r1.union(r2)
        assertEquals(listOf(A, B, C, D), insertionOrder(rUnion))
        val r3 = Rangeable<Letter>()
        r3.insert(B, 0, 100); r3.insert(D, 0, 100); r3.insert(A, 0, 100)
        val rIntersect = rUnion.intersect(r3)
        assertEquals(listOf(A, B, D), insertionOrder(rIntersect)) // C dropped
        val ord = ordOf(rIntersect)
        assertEquals(1, ord[A]); assertEquals(2, ord[B]); assertEquals(3, ord[D])
    }

    @Test fun `G80 empty result eager prune across set-op chain`() {
        val r1 = Rangeable<Letter>()
        r1.insert(A, 0, 5); r1.insert(B, 10, 15)
        val r2 = Rangeable<Letter>()
        r2.insert(A, 100, 200); r2.insert(B, 100, 200)
        val r3 = r1.intersect(r2)
        assertTrue(r3.isEmpty)
        val r4 = r3.union(r1)
        assertEquals(listOf(A, B), insertionOrder(r4))
        val ord = ordOf(r4)
        assertEquals(1, ord[A]); assertEquals(2, ord[B])
    }

    // ====================================================================== //
    // Kotlin-specific: operator overloads + InPlace + return-this chaining
    // ====================================================================== //

    @Test fun `K01 plus operator is union`() {
        val r1 = Rangeable<Any>(); r1.insert(Strong(), 0, 5)
        val r2 = Rangeable<Any>(); r2.insert(Italic(), 10, 15)
        val r3 = r1 + r2
        assertEquals(2, r3.size)
        assertEquals(listOf<Any>(Strong(), Italic()), insertionOrder(r3))
    }

    @Test fun `K02 minus operator is difference`() {
        val r1 = Rangeable<Strong>(); r1.insert(Strong(), 0, 10)
        val r2 = Rangeable<Strong>(); r2.insert(Strong(), 3, 6)
        val r3 = r1 - r2
        assertEquals(listOf(0 to 2, 7 to 10), pairs(r3.getRange(Strong())))
    }

    @Test fun `K03 plusAssign mutates and bumps version on change`() {
        val r1 = Rangeable<Any>(); r1.insert(Strong(), 0, 5)
        val v0 = r1.version
        val r2 = Rangeable<Any>(); r2.insert(Italic(), 10, 15)
        r1 += r2
        assertEquals(2, r1.size)
        assertEquals(listOf<Any>(Strong(), Italic()), insertionOrder(r1))
        assertEquals(v0 + 1, r1.version)
    }

    @Test fun `K04 minusAssign mutates and bumps version on change`() {
        val r1 = Rangeable<Strong>(); r1.insert(Strong(), 0, 10)
        val v0 = r1.version
        val r2 = Rangeable<Strong>(); r2.insert(Strong(), 3, 6)
        r1 -= r2
        assertEquals(listOf(0 to 2, 7 to 10), pairs(r1.getRange(Strong())))
        assertEquals(v0 + 1, r1.version)
    }

    @Test fun `K05 InPlace forms return this for chaining`() {
        val r1 = Rangeable<Any>(); r1.insert(Strong(), 0, 5)
        val r2 = Rangeable<Any>(); r2.insert(Italic(), 10, 15)
        val out = r1.unionInPlace(r2)
        assertTrue(out === r1)
    }

    @Test fun `K06 removal methods return this for chaining`() {
        val r = Rangeable<Any>().insert(Strong(), 0, 5).insert(Italic(), 10, 15)
        val out = r.remove(Strong()).remove(Italic(), 10, 12)
        assertTrue(out === r)
        assertEquals(listOf(13 to 15), pairs(r.getRange(Italic())))
    }

    @Test fun `K07 unionInPlace does not bump when result equals self`() {
        val r1 = Rangeable<Any>(); r1.insert(Strong(), 0, 10)
        val v0 = r1.version
        val r2 = Rangeable<Any>(); r2.insert(Strong(), 3, 6) // ⊂ r1
        r1.unionInPlace(r2)
        assertEquals(v0, r1.version)
    }

    @Test fun `K08 intersectInPlace does not bump when result equals self`() {
        val r1 = Rangeable<Any>(); r1.insert(Strong(), 0, 10)
        val v0 = r1.version
        // r2 is a superset of r1 in every key → intersect == r1.
        val r2 = Rangeable<Any>(); r2.insert(Strong(), -100, 100)
        r1.intersectInPlace(r2)
        assertEquals(v0, r1.version)
        assertEquals(listOf(0 to 10), pairs(r1.getRange(Strong())))
    }

    @Test fun `K09 differenceInPlace does not bump when result equals self`() {
        val r1 = Rangeable<Any>(); r1.insert(Strong(), 0, 10)
        val v0 = r1.version
        val r2 = Rangeable<Any>(); r2.insert(Italic(), 100, 200) // disjoint key
        r1.differenceInPlace(r2)
        assertEquals(v0, r1.version)
        assertEquals(listOf(0 to 10), pairs(r1.getRange(Strong())))
    }

    @Test fun `K10 symmetricDifferenceInPlace does not bump when result equals self`() {
        val r1 = Rangeable<Any>(); r1.insert(Strong(), 0, 10)
        val v0 = r1.version
        val r2 = Rangeable<Any>() // empty other
        r1.symmetricDifferenceInPlace(r2)
        assertEquals(v0, r1.version)
        assertEquals(listOf(0 to 10), pairs(r1.getRange(Strong())))
    }

    @Test fun `K11 intMaxSentinel propagates through non-mutating union`() {
        val intMax = Int.MAX_VALUE
        val r1 = Rangeable<Strong>(intMaxSentinel = intMax)
        r1.insert(Strong(), 100, intMax)
        val r2 = Rangeable<Strong>(intMaxSentinel = intMax)
        r2.insert(Strong(), 50, 90)
        val r3 = r1.union(r2)
        // Transitions on the result must produce a null close coord.
        val events = r3.transitions(0, intMax)
        // Events: open@50 (from r2), close@100 NO — actually they merge to one open
        // Wait: union of (100..max) and (50..90) is two disjoint entries since 90+1<100.
        // Actually 90+1=91 < 100 so non-adjacent → two entries (50..90) and (100..max).
        // Open@50, Close@91, Open@100, Close@null.
        assertEquals(50, events[0].coordinate)
        assertEquals(TransitionKind.OPEN, events[0].kind)
        assertEquals(91, events[1].coordinate)
        assertEquals(TransitionKind.CLOSE, events[1].kind)
        assertEquals(100, events[2].coordinate)
        assertEquals(TransitionKind.OPEN, events[2].kind)
        assertEquals(null, events[3].coordinate)
        assertEquals(TransitionKind.CLOSE, events[3].kind)
    }

    @Test fun `K12 intMaxSentinel propagates through non-mutating intersect`() {
        val intMax = Int.MAX_VALUE
        val r1 = Rangeable<Strong>(intMaxSentinel = intMax)
        r1.insert(Strong(), 100, intMax)
        val r2 = Rangeable<Strong>(intMaxSentinel = intMax)
        r2.insert(Strong(), 200, intMax)
        val r3 = r1.intersect(r2)
        val events = r3.transitions(0, intMax)
        assertEquals(2, events.size)
        assertEquals(200, events[0].coordinate)
        assertEquals(TransitionKind.OPEN, events[0].kind)
        assertEquals(null, events[1].coordinate)
        assertEquals(TransitionKind.CLOSE, events[1].kind)
    }

    @Test fun `K13 boundary index invalidated after set-op InPlace`() {
        val r1 = Rangeable<Any>(); r1.insert(Strong(), 0, 10)
        // Force build.
        assertEquals(listOf<Any>(Strong()), r1[5].objs)
        val r2 = Rangeable<Any>(); r2.insert(Italic(), 5, 15)
        r1 += r2
        // After the in-place union, at coord 7 BOTH must be active.
        assertEquals(listOf<Any>(Strong(), Italic()), r1[7].objs)
    }

    @Test fun `K14 union does not mutate sources even with overlapping keys`() {
        val r1 = Rangeable<Any>(); r1.insert(Strong(), 0, 10)
        val r2 = Rangeable<Any>(); r2.insert(Strong(), 20, 30)
        val v1 = r1.version
        val v2 = r2.version
        val r3 = r1.union(r2)
        assertEquals(listOf(0 to 10), pairs(r1.getRange(Strong())))
        assertEquals(listOf(20 to 30), pairs(r2.getRange(Strong())))
        assertEquals(v1, r1.version)
        assertEquals(v2, r2.version)
        assertEquals(listOf(0 to 10, 20 to 30), pairs(r3.getRange(Strong())))
    }

    @Test fun `K15 removal methods do not affect insertion-order of other elements via re-insert`() {
        // Regression: ord must not be silently re-bumped on a no-op.
        val r = Rangeable<Letter>()
        r.insert(A, 0, 5); r.insert(B, 10, 15); r.insert(C, 20, 25)
        val v0 = r.version
        r.remove(A, 100, 200) // no overlap on A
        assertEquals(v0, r.version)
        assertEquals(listOf(A, B, C), insertionOrder(r))
        val ord = ordOf(r)
        assertEquals(1, ord[A]); assertEquals(2, ord[B]); assertEquals(3, ord[C])
    }

    @Test fun `K16 chained removal and re-insert preserves R14 semantics`() {
        val r = Rangeable<Letter>()
        r.insert(A, 0, 5).insert(B, 10, 15).remove(A).insert(A, 100, 110)
        assertEquals(listOf(B, A), insertionOrder(r))
    }
}
