# split-records-tab-performance-and-laps artifacts review

Date: 2026-04-30
Change: `split-records-tab-performance-and-laps`
Scope: OpenSpec artifacts review for Records tab PERFORMANCE/LAPS segmented UI split.

## Result

PASS. No P1/P2 findings.

`openspec validate split-records-tab-performance-and-laps --strict` passes.

## Reviewed Artifacts

- `openspec/changes/split-records-tab-performance-and-laps/proposal.md`
- `openspec/changes/split-records-tab-performance-and-laps/design.md`
- `openspec/changes/split-records-tab-performance-and-laps/specs/records-home-segmented-views/spec.md`
- `openspec/changes/split-records-tab-performance-and-laps/tasks.md`

## Decision Review

1. SPEED CURVE Canvas stub: Accepted.
   - No new chart dependency.
   - Stub is scoped to placeholder UI and future data-backed chart replacement is explicitly listed as follow-up.
   - Tasks avoid complex Canvas text measurement by allowing Compose overlay text for the bubble.

2. Track preview Canvas stub: Accepted.
   - Keeps this round visual-only.
   - Does not claim real track geometry.
   - Future replacement with real catalog geometry is explicitly outside scope.

3. PB highlight: Accepted.
   - `TrackTechRow` has no accent/tint parameter today, but tasks correctly say not to extend the shared API just for this placeholder round.
   - Trophy icon plus `Personal Best` subtitle is enough for this scope.

4. Filter icon placeholder: Accepted.
   - The icon remains above the segmented branch, so both views show it.
   - Toast-only behavior is clearly marked as placeholder and filter implementation is follow-up.

5. Hardcoded private data classes: Accepted.
   - Data is constrained to `RecordsHomeScreen.kt`.
   - Spec includes private visibility and no data-layer import gates.
   - This avoids prematurely creating domain/API models for placeholder content.

6. No new unit test: Accepted for this round.
   - The change is a single-file UI placeholder split with private data only.
   - The artifacts include grep/static gates plus manual visual gates.
   - Real repository/ViewModel data wiring is deferred to a future round where unit tests will be meaningful.

## Notes

- `feature/test/build.gradle.kts` already includes `androidx.compose.material:material-icons-extended`, so the proposed Filter / trophy / location / star icon choices are viable.
- `MetricTile` and `CutCornerPanel` APIs match the planned usage.
- `TrackTechRow` does not support accent color; use the planned fallback rather than expanding the shared component API.

## Apply Gate

Greenlight to run:

```bash
/opsx:apply split-records-tab-performance-and-laps
```

## Code Review After Apply

Result: PASS. No P1/P2 findings.

Reviewed implementation:

- `feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/RecordsHomeScreen.kt`

Checks:

- `openspec validate split-records-tab-performance-and-laps --strict`: PASS
- `openspec validate switch-tab-shell-to-horizontal-pager --strict`: PASS
- `openspec validate unify-gps-telemetry-persistence --strict`: PASS
- `./gradlew :feature:test:compileDebugKotlin`: PASS
- `./gradlew :feature:test:testDebugUnitTest`: PASS
- `./gradlew :app:compileDebugKotlin`: PASS
- `./gradlew :core:data:testDebugUnitTest`: PASS
- `./gradlew :core:domain:test :core:bluetooth:testDebugUnitTest`: PASS

Implementation review:

- Records segmented body now uses `when (selectedSegment)` and dispatches to separate `PerformanceView` / `LapsView`.
- Filter icon is above the segmented branch and visible to both views.
- Placeholder data is private and constrained to `RecordsHomeScreen.kt`.
- No data-layer imports or ViewModel/Repository parameters were introduced.
- `SegmentedControl` behavior remains visually compatible with baseline.

Non-blocking note:

- The SPEED CURVE Canvas draws axis lines and ticks, but not numeric tick labels. Manual visual signoff accepted the placeholder shape; if stricter chart readability is desired, add numeric labels in the future real chart round.
