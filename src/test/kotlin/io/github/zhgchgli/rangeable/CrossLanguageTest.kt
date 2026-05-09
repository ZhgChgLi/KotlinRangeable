package io.github.zhgchgli.rangeable

import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Cross-language fixture replay.
 *
 * Consumes the shared `cross_language.json` produced by Ruby
 * (`RubyRangeable/test/cross_language_fixture.rb`) and replayed
 * identically by Swift, Python and JS. Verifies all 86 probes
 * (subscript + transitions) against the Ruby-produced expected values.
 */
class CrossLanguageTest {

    private data class Strong(val tag: String = "strong")
    private data class Italic(val tag: String = "italic")
    private data class Code(val tag: String = "code")
    private data class Link(val url: String)

    private val elementFactory: List<() -> Any> = listOf(
        { Strong() },
        { Italic() },
        { Code() },
        { Link("a") },
        { Link("b") },
    )

    private fun canonicalKey(element: Any): String = when (element) {
        is Strong -> "strong"
        is Italic -> "italic"
        is Code -> "code"
        is Link -> "link:${element.url}"
        else -> error("unknown element $element")
    }

    private val fixture: JSONObject by lazy {
        val text = javaClass.classLoader
            .getResourceAsStream("cross_language.json")
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: error("cross_language.json not on test classpath")
        JSONObject(text)
    }

    private val replayed: Rangeable<Any> by lazy {
        // The fixture contains one op whose `end` is 2^62 - 1 (Ruby's
        // simulator for Int.max). Clamp to Kotlin's Int.MAX_VALUE and
        // run with intMaxSentinel = Int.MAX_VALUE so the close coord
        // becomes null (+∞) per RFC §4.7 C4 — matching how Ruby /
        // Python represent that op.
        val r = Rangeable<Any>(intMaxSentinel = Int.MAX_VALUE)
        val ops = fixture.getJSONArray("ops")
        for (i in 0 until ops.length()) {
            val op = ops.getJSONObject(i)
            val e = elementFactory[op.getInt("element")]()
            val startLong = op.getLong("start")
            val endLong = op.getLong("end")
            val start = startLong.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
            val end = endLong.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
            try {
                r.insert(e, start, end)
            } catch (_: InvalidIntervalException) {
                // Fixture is generated with start <= end; this guards
                // future mis-edits to the fixture.
            }
        }
        r
    }

    @Test fun `subscript probes match Ruby-produced expected outputs`() {
        val probes = fixture.getJSONArray("probes")
        var checked = 0
        var failures = 0
        for (i in 0 until probes.length()) {
            val probe = probes.getJSONObject(i)
            if (probe.getString("kind") != "subscript") continue
            val coord = probe.getInt("i")
            val expectedJson = probe.getJSONArray("expected")
            val expected = (0 until expectedJson.length()).map { expectedJson.getString(it) }
            val actual = replayed[coord].objs.map { canonicalKey(it) }
            if (actual != expected) {
                failures += 1
                if (failures == 1) {
                    println("first subscript mismatch at i=$coord: expected=$expected actual=$actual")
                }
            }
            checked += 1
        }
        assertEquals(0, failures, "subscript probe failures: $failures of $checked")
    }

    @Test fun `transitions probes match Ruby-produced expected outputs`() {
        val probes = fixture.getJSONArray("probes")
        var checked = 0
        var failures = 0
        for (i in 0 until probes.length()) {
            val probe = probes.getJSONObject(i)
            if (probe.getString("kind") != "transitions") continue
            val lo = probe.getInt("lo")
            val hi = probe.getInt("hi")
            val expected = parseExpectedEvents(probe.getJSONArray("expected"))
            val actual = replayed.transitions(lo, hi).map { ev ->
                ExpectedEvent(ev.coordinate, ev.kind.tag, canonicalKey(ev.element))
            }
            if (actual != expected) {
                failures += 1
                if (failures == 1) {
                    println("first transitions mismatch at lo=$lo hi=$hi: expected=$expected actual=$actual")
                }
            }
            checked += 1
        }
        assertEquals(0, failures, "transitions probe failures: $failures of $checked")
    }

    private data class ExpectedEvent(val coordinate: Int?, val kind: String, val element: String)

    private fun parseExpectedEvents(arr: JSONArray): List<ExpectedEvent> =
        (0 until arr.length()).map { idx ->
            val o = arr.getJSONObject(idx)
            val coord: Int? = if (o.isNull("coordinate")) null else o.getInt("coordinate")
            ExpectedEvent(coord, o.getString("kind"), o.getString("element"))
        }
}
