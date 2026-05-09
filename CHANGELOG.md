# Changelog

All notable changes to this project will be documented here. The format is
based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and this
project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.0] - 2026-05-10

Initial public release of the Kotlin/JVM reference implementation of the
[Rangeable RFC](https://github.com/ZhgChgLi/RangeableRFC).

### Added
- `Rangeable<E : Any>` generic container with the full RFC §3 API:
  `insert`, `get` (operator), `getRange`, `transitions`, `copy`,
  `iterator` (operator), `size`, `isEmpty`, `version`.
- `Interval`, `Slot<E>`, `TransitionEvent<E>`, `TransitionKind` value
  types (Kotlin `data class` / `enum class`).
- `RangeableException` and `InvalidIntervalException` (`RuntimeException`
  subclasses).
- Built with Kotlin 2.1.0; JVM bytecode target 11; sources + Javadoc JARs.

### Verified
- 23 RFC §10 contract tests.
- 86 cross-language probes against the shared 160-op fixture (sha256
  `316ac8619fd632174b2374ed2137348e8d744e3904b002761d0dbdce38ea2edf`,
  byte-identical to the Ruby, Swift, Python and JS fixtures).
- Property test against a brute-force oracle over 1000 random ops.

### Distribution
- Available via JitPack:
  ```kotlin
  repositories { maven("https://jitpack.io") }
  dependencies { implementation("com.github.ZhgChgLi:KotlinRangeable:v1.0.0") }
  ```
