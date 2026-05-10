# KotlinRangeable

[![JitPack](https://jitpack.io/v/ZhgChgLi/KotlinRangeable.svg)](https://jitpack.io/#ZhgChgLi/KotlinRangeable)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1-blue?logo=kotlin)](https://kotlinlang.org)
[![JVM](https://img.shields.io/badge/JVM-11%2B-blue)](https://openjdk.org)
[![License](https://img.shields.io/badge/license-MIT-blue)](LICENSE)

Reference Kotlin/JVM implementation of [`Rangeable<Element>`](https://github.com/ZhgChgLi/RangeableRFC) — a generic, integer-coordinate, closed-interval set container with first-insert ordered active queries.

## Installation

Add JitPack to your repositories and depend on the tagged release:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://jitpack.io")
    }
}

// build.gradle.kts
dependencies {
    implementation("com.github.ZhgChgLi:KotlinRangeable:v1.0.0")
}
```

Or in Groovy DSL:

```groovy
repositories {
    maven { url 'https://jitpack.io' }
}
dependencies {
    implementation 'com.github.ZhgChgLi:KotlinRangeable:v1.0.0'
}
```

## Usage

```kotlin
import io.github.zhgchgli.rangeable.Rangeable

data class Strong(val tag: String = "strong")
data class Italic(val tag: String = "italic")
data class Link(val url: String)

val r = Rangeable<Any>()
r.insert(Strong(), start = 2, end = 5)
r.insert(Strong(), start = 3, end = 7)        // merges with [2, 5] → [2, 7]
r.insert(Strong(), start = 9, end = 11)       // disjoint
r.insert(Italic(), start = 3, end = 8)

r.getRange(Strong())        // [Interval(2, 7), Interval(9, 11)]
r.getRange(Italic())        // [Interval(3, 8)]

r[4].objs                   // [Strong(...), Italic(...)]   first-insert order
r[8].objs                   // [Italic(...)]
r[10].objs                  // [Strong(...)]
```

### Sweep iteration via transitions

```kotlin
for (event in r.transitions(from = 0, to = 15)) {
    println("${event.coordinate} ${event.kind} ${event.element}")
}
```

## API

| Member | Returns | Notes |
|---|---|---|
| `Rangeable<E : Any>()` | constructor | empty container |
| `r.insert(e, start, end)` | `Rangeable<E>` (chainable) | throws `InvalidIntervalException` on `start > end` |
| `r[i]` | `Slot<E>` | `Slot.objs` is the active-set list |
| `r.getRange(e)` | `List<Interval>` | merged disjoint ranges |
| `r.transitions(from, to)` | `List<TransitionEvent<E>>` | `to = null` means +∞ |
| `r.size` | `Int` | distinct elements |
| `r.isEmpty` | `Boolean` | |
| `for (pair in r)` | `Iterator<Pair<E, List<Interval>>>` | first-insert order |
| `r.copy()` | `Rangeable<E>` | deep copy |
| `r.version` | `Int` | unchanged on idempotent insert |

## Element equality

`Rangeable<E : Any>` keys elements by `equals` / `hashCode`. Kotlin
`data class` auto-generates these from primary-constructor properties,
which is the easiest way to satisfy the contract:

```kotlin
data class Strong(val tag: String = "strong")  // Strong() == Strong()
data class Link(val url: String)               // Link("a") != Link("b")
```

For non-data classes, override `equals` and `hashCode` consistently per
RFC §4.2 (E1–E5).

## Semantics

- **End is inclusive**: `insert(e, start = a, end = b)` covers `a..b`, both ends.
- **Same-element merging**: equal elements merge on overlap or integer adjacency. `[2, 4] ∪ [5, 7] = [2, 7]`.
- **Idempotent insert**: re-inserting a contained interval does not bump `version`.
- **Out-of-order rejected**: `insert(e, 5, 2)` throws `InvalidIntervalException`.
- **Active-set ordering**: deterministic — first-insert order of the element.
- **Coordinate sentinel**: a close event for an interval ending at the optional `intMaxSentinel` carries `coordinate == null` (null == +∞ per RFC §4.7).

See [RangeableRFC](https://github.com/ZhgChgLi/RangeableRFC) § 4 for normative semantics and § 10 for the 23-case test contract.

## Cross-language consistency

This Kotlin implementation joins the [Ruby](https://github.com/ZhgChgLi/RubyRangeable), [Swift](https://github.com/ZhgChgLi/SwiftRangeable), [Python](https://github.com/ZhgChgLi/PythonRangeable), [JS](https://github.com/ZhgChgLi/JSRangeable) and [Go](https://github.com/ZhgChgLi/GoRangeable) implementations. All six share a 160-op / 86-probe JSON fixture and produce byte-identical outputs.

## See also

- **[RangeableRFC](https://github.com/ZhgChgLi/RangeableRFC)** — normative specification.
- **[RubyRangeable](https://github.com/ZhgChgLi/RubyRangeable)** — Ruby reference (`gem install rangeable`).
- **[SwiftRangeable](https://github.com/ZhgChgLi/SwiftRangeable)** — Swift reference (SPM).
- **[PythonRangeable](https://github.com/ZhgChgLi/PythonRangeable)** — Python reference (`pip install rangeable`).
- **[JSRangeable](https://github.com/ZhgChgLi/JSRangeable)** — TypeScript reference (`npm i rangeable-js`).
- **[GoRangeable](https://github.com/ZhgChgLi/GoRangeable)** — Go reference (`go get github.com/ZhgChgLi/GoRangeable`).

## Development

```bash
./gradlew test            # run JUnit5 suite
./gradlew jar             # produce build/libs/rangeable-1.0.0.jar
./gradlew publishToMavenLocal   # smoke-test publishing config
```

The suite covers the full RFC § 10 contract, the cross-language fixture replay (`org.json`-based parser), and a property test against a brute-force oracle.

## License

MIT (c) ZhgChgLi
