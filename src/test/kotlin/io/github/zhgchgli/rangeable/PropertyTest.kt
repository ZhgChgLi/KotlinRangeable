package io.github.zhgchgli.rangeable

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * RFC §10 Test #20 — random insert + brute-force oracle parity.
 *
 * Uses Kotlin's [kotlin.random.Random] with a fixed seed for
 * reproducibility within the JVM. Each language port runs its own
 * seeded property test; the cross-language byte-identical fixture is
 * the canonical interop test (see [CrossLanguageTest]).
 */
class PropertyTest {

    private data class Strong(val tag: String = "strong")
    private data class Italic(val tag: String = "italic")
    private data class Code(val tag: String = "code")
    private data class Link(val url: String)

    private val elements: List<Any> = listOf(
        Strong(), Italic(), Code(), Link("x"), Link("y"),
    )

    private data class Op(val e: Any, val lo: Int, val hi: Int)

    @Test fun `random inserts produce active sets identical to brute force`() {
        val rng = kotlin.random.Random(42)
        val coordBound = 200
        val nOps = 1000

        val ops = (0 until nOps).map {
            val e = elements[rng.nextInt(elements.size)]
            val lo = rng.nextInt(-coordBound, coordBound + 1)
            val hi = lo + rng.nextInt(0, 31)
            Op(e, lo, hi)
        }

        val r = Rangeable<Any>()
        for (op in ops) r.insert(op.e, op.lo, op.hi)

        // First-seen index per element.
        val firstSeen = HashMap<Any, Int>()
        ops.forEachIndexed { idx, op ->
            firstSeen.getOrPut(op.e) { idx }
        }

        var failures = 0
        var sample: Triple<Int, List<Any>, List<Any>>? = null
        for (i in -coordBound..coordBound) {
            val expected = bruteForceActive(ops, firstSeen, i)
            val actual = r[i].objs
            if (actual != expected) {
                failures += 1
                if (sample == null) sample = Triple(i, expected, actual)
            }
        }
        assertEquals(0, failures, "first mismatch=$sample")
    }

    private fun bruteForceActive(
        ops: List<Op>,
        firstSeen: Map<Any, Int>,
        i: Int,
    ): List<Any> {
        val active = LinkedHashMap<Any, Any>()
        for (op in ops) {
            if (op.lo <= i && i <= op.hi) {
                active.putIfAbsent(op.e, op.e)
            }
        }
        return active.keys.sortedBy { firstSeen.getValue(it) }
    }
}
