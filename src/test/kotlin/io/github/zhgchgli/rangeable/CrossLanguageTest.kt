package io.github.zhgchgli.rangeable

import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Cross-language fixture conformance test (Kotlin side).
 *
 * Re-runs the deterministic fixture in
 * `src/test/resources/cross_language.json` through the live `Rangeable`
 * implementation and verifies every probe and set-op `expected_state`
 * matches. The same fixture is shipped to the Ruby / Swift / Python /
 * JS / Go reference implementations; a green run here is a prerequisite
 * for cross-language byte-identity claims.
 *
 * Schema versions handled:
 *   v1 — no `schema_version`, only `ops` (all `insert`) + `probes`.
 *   v2 — `schema_version: 2`, `ops` may include `remove`/`remove_element`/
 *        `clear`/`remove_ranges`, plus a `set_ops` array. Probes carry an
 *        optional `phase` field that selects which intermediate state they
 *        were computed against (`v1`-style: post all v1 ops only;
 *        `after_removes`: post v1 ops + first 30 `remove` ops only;
 *        `final`: post all 200 ops).
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

    // The fixture sometimes encodes Ruby's Int.max as 2^62 - 1 to mark a
    // "+∞" close coord (RFC §4.7 C4). Clamp such ops to Kotlin's Int.MAX
    // and configure the live container with the matching sentinel so the
    // close event lands on `null` (matching Ruby / Python's representation).
    private fun newRangeable(): Rangeable<Any> = Rangeable(intMaxSentinel = Int.MAX_VALUE)

    private fun clamp(v: Long): Int =
        v.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()

    @Test fun `fixture round trip matches reference outputs`() {
        val schemaVersion = if (fixture.has("schema_version")) fixture.getInt("schema_version") else 1
        when (schemaVersion) {
            1 -> runV1()
            2 -> runV2()
            else -> fail("unsupported schema_version: $schemaVersion")
        }
    }

    // ====================================================================== //
    // v1 runner
    // ====================================================================== //

    private fun runV1() {
        val r = newRangeable()
        val ops = fixture.getJSONArray("ops")
        for (i in 0 until ops.length()) {
            applyOp(r, ops.getJSONObject(i))
        }
        val probes = fixture.getJSONArray("probes")
        for (i in 0 until probes.length()) {
            assertProbe(r, probes.getJSONObject(i))
        }
    }

    // ====================================================================== //
    // v2 runner
    // ====================================================================== //

    private fun runV2() {
        val ops = fixture.getJSONArray("ops")

        // Index v1 op boundary: the last `insert` before any non-insert op.
        // In our generator this is exactly index 161 (160 random inserts + 1
        // boundary sentinel insert). Compute it dynamically so the runner
        // stays robust if the generator's tail composition changes.
        var v1Boundary = ops.length()
        for (i in 0 until ops.length()) {
            val opKind = ops.getJSONObject(i).optString("op", "insert")
            if (opKind != "insert") {
                v1Boundary = i
                break
            }
        }

        // Snapshot 1: r_v1 = state after ops[0 until v1Boundary] (all inserts).
        val rV1 = newRangeable()
        for (i in 0 until v1Boundary) applyOp(rV1, ops.getJSONObject(i))

        // Snapshot 2: r_after_removes = r_v1 + first 30 `remove` ops only
        // (skipping remove_element/clear/remove_ranges that follow).
        val rAfterRemoves = cloneViaOps(ops, 0, v1Boundary)
        var removeTaken = 0
        var i = v1Boundary
        while (i < ops.length() && removeTaken < 30) {
            val op = ops.getJSONObject(i)
            if (op.optString("op", "insert") == "remove") {
                applyOp(rAfterRemoves, op)
                removeTaken += 1
            }
            i += 1
        }

        // Snapshot 3: r_final = state after applying ALL ops in order.
        val rFinal = cloneViaOps(ops, 0, v1Boundary)
        for (j in v1Boundary until ops.length()) applyOp(rFinal, ops.getJSONObject(j))

        // Dispatch each probe to the matching snapshot.
        val probes = fixture.getJSONArray("probes")
        for (k in 0 until probes.length()) {
            val probe = probes.getJSONObject(k)
            val phase = if (probe.isNull("phase")) null else probe.optString("phase", null)
            val target = when (phase) {
                null, "v1" -> rV1
                "after_removes" -> rAfterRemoves
                "final" -> rFinal
                else -> { fail("unknown probe phase: $phase"); return }
            }
            assertProbe(target, probe)
        }

        // Set-op validation. Each entry: build self + other (and optional
        // chain), apply the non-mutating op, then check `expected_state`
        // (insertion_order + intervals) and the `expected` payload of every
        // probe.
        if (fixture.has("set_ops") && !fixture.isNull("set_ops")) {
            val setOps = fixture.getJSONArray("set_ops")
            for (k in 0 until setOps.length()) {
                verifySetOp(setOps.getJSONObject(k))
            }
        }
    }

    // Replay a slice of ops on a fresh Rangeable; helper so the post-v1,
    // post-after-removes, and post-final snapshots are all independent
    // (mutating one MUST NOT affect the others).
    private fun cloneViaOps(ops: JSONArray, fromInclusive: Int, toExclusive: Int): Rangeable<Any> {
        val r = newRangeable()
        for (i in fromInclusive until toExclusive) {
            applyOp(r, ops.getJSONObject(i))
        }
        return r
    }

    private fun applyOp(r: Rangeable<Any>, op: JSONObject) {
        val kind = op.optString("op", "insert")
        when (kind) {
            "insert" -> {
                val e = elementFactory[op.getInt("element")]()
                val start = clamp(op.getLong("start"))
                val end = clamp(op.getLong("end"))
                try {
                    r.insert(e, start, end)
                } catch (_: InvalidIntervalException) {
                    // Fixture is generated with start <= end; this guards
                    // future mis-edits to the fixture.
                }
            }
            "remove" -> {
                val e = elementFactory[op.getInt("element")]()
                val start = clamp(op.getLong("start"))
                val end = clamp(op.getLong("end"))
                r.remove(e, start, end)
            }
            "remove_element" -> {
                val e = elementFactory[op.getInt("element")]()
                r.remove(e)
            }
            "clear" -> {
                r.clear()
            }
            "remove_ranges" -> {
                val start = clamp(op.getLong("start"))
                val end = clamp(op.getLong("end"))
                r.removeRanges(start, end)
            }
            else -> fail("unknown op kind: $kind")
        }
    }

    private fun assertProbe(r: Rangeable<Any>, probe: JSONObject) {
        val phase = if (probe.isNull("phase")) "v1" else probe.optString("phase", "v1")
        when (val kind = probe.getString("kind")) {
            "subscript" -> {
                val coord = probe.getInt("i")
                val expectedJson = probe.getJSONArray("expected")
                val expected = (0 until expectedJson.length()).map { expectedJson.getString(it) }
                val actual = r[coord].objs.map { canonicalKey(it) }
                assertEquals(
                    expected, actual,
                    "subscript mismatch (phase=$phase, i=$coord)",
                )
            }
            "transitions" -> {
                val lo = probe.getInt("lo")
                val hi = probe.getInt("hi")
                val expected = parseExpectedEvents(probe.getJSONArray("expected"))
                val actual = r.transitions(lo, hi).map { ev ->
                    ExpectedEvent(ev.coordinate, ev.kind.tag, canonicalKey(ev.element))
                }
                assertEquals(
                    expected, actual,
                    "transitions mismatch (phase=$phase, lo=$lo, hi=$hi)",
                )
            }
            else -> fail("unknown probe kind: $kind")
        }
    }

    private fun verifySetOp(entry: JSONObject) {
        val id = entry.getString("id")
        val opName = entry.getString("op")

        val selfR = buildFromOps(entry.getJSONArray("self_ops"))
        val otherR = buildFromOps(entry.getJSONArray("other_ops"))
        var result = applySetOp(selfR, otherR, opName)
        if (entry.has("chain_ops") && !entry.isNull("chain_ops")) {
            val chainR = buildFromOps(entry.getJSONArray("chain_ops"))
            result = applySetOp(result, chainR, opName)
        }

        val expectedState = entry.getJSONObject("expected_state")
        val (expectedOrder, expectedIntervals) = decodeExpectedState(expectedState)
        val (actualOrder, actualIntervals) = serialiseState(result)
        assertEquals(
            expectedOrder, actualOrder,
            "set_op $id: insertion_order mismatch",
        )
        assertEquals(
            expectedIntervals, actualIntervals,
            "set_op $id: intervals mismatch",
        )

        if (entry.has("probes") && !entry.isNull("probes")) {
            val probes = entry.getJSONArray("probes")
            for (i in 0 until probes.length()) {
                assertProbe(result, probes.getJSONObject(i))
            }
        }
    }

    private fun buildFromOps(ops: JSONArray): Rangeable<Any> {
        val r = newRangeable()
        for (i in 0 until ops.length()) {
            applyOp(r, ops.getJSONObject(i))
        }
        return r
    }

    private fun applySetOp(self: Rangeable<Any>, other: Rangeable<Any>, name: String): Rangeable<Any> =
        when (name) {
            "union" -> self.union(other)
            "intersect" -> self.intersect(other)
            "difference" -> self.difference(other)
            "symmetric_difference" -> self.symmetricDifference(other)
            else -> { fail("unknown set op: $name"); throw IllegalStateException() }
        }

    /**
     * Snapshot a Rangeable into the schema's `expected_state` shape:
     *   first = insertion_order  : List<canonical key>
     *   second = intervals       : LinkedHashMap<canonical key, List<[lo,hi]>>
     *
     * Both halves use canonical keys (strong/italic/code/link:a/link:b)
     * so they line up byte-identical with what the Ruby generator wrote.
     */
    private fun serialiseState(r: Rangeable<Any>): Pair<List<String>, Map<String, List<List<Int>>>> {
        val order = ArrayList<String>()
        val intervals = LinkedHashMap<String, List<List<Int>>>()
        for ((element, pairs) in r) {
            val key = canonicalKey(element)
            order += key
            intervals[key] = pairs.map { listOf(it.lo, it.hi) }
        }
        return order to intervals
    }

    private fun decodeExpectedState(state: JSONObject): Pair<List<String>, Map<String, List<List<Int>>>> {
        val orderJson = state.getJSONArray("insertion_order")
        val order = (0 until orderJson.length()).map { orderJson.getString(it) }
        val intervalsJson = state.getJSONObject("intervals")
        val intervals = LinkedHashMap<String, List<List<Int>>>()
        for (key in intervalsJson.keys()) {
            val arr = intervalsJson.getJSONArray(key)
            val pairs = (0 until arr.length()).map { idx ->
                val pair = arr.getJSONArray(idx)
                listOf(pair.getInt(0), pair.getInt(1))
            }
            intervals[key] = pairs
        }
        return order to intervals
    }

    private data class ExpectedEvent(val coordinate: Int?, val kind: String, val element: String)

    private fun parseExpectedEvents(arr: JSONArray): List<ExpectedEvent> =
        (0 until arr.length()).map { idx ->
            val o = arr.getJSONObject(idx)
            val coord: Int? = if (o.isNull("coordinate")) null else o.getInt("coordinate")
            ExpectedEvent(coord, o.getString("kind"), o.getString("element"))
        }
}
