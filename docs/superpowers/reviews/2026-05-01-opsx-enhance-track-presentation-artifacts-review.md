# Review: enhance-track-presentation Artifacts

Date: 2026-05-01
Change: `enhance-track-presentation`
Scope: OpenSpec artifacts review before `/opsx:apply`

## Result

Changes requested.

`openspec validate enhance-track-presentation --strict` passes, but the task plan has two implementation-blocking inconsistencies.

## Findings

### [P1] `placeholder for future round` grep gate conflicts with preserved START LAP SESSION toast

File: `openspec/changes/enhance-track-presentation/tasks.md:116`

Task 9.4 says to confirm `LapsHomeScreen.kt` has no `"placeholder for future round"` string, while the same sentence says START LAP SESSION's toast should be preserved. The current preserved toast is `"Lap session entry — placeholder for future round"`, so the broad substring grep will still match even after CHANGE TRACK is correctly migrated. Narrow the gate to the exact removed string, e.g. `"Track selection — placeholder for future round"`, or scope it to the CHANGE TRACK block.

### [P1] Robolectric Compose tests are required but test dependencies/tasks do not add them

File: `openspec/changes/enhance-track-presentation/tasks.md:26`
Also: `tasks.md:69-72`, `tasks.md:103-107`, `feature/test/build.gradle.kts:82-91`

Tasks require `PresetTrackAssetTest` with `RuntimeEnvironment`, plus `TrackThumbnailTest` and `SelectTrackBottomSheetTest` as Robolectric Compose tests under `src/test`, and `11.1` runs them via `:feature:test:testDebugUnitTest`. The module currently only has Compose UI test deps in `androidTestImplementation`, no `testImplementation` for Robolectric or Compose UI test. Applying as written will fail to compile those local tests unless the tasks explicitly add the needed test dependencies, or the tests are moved to instrumentation and the verification command is updated.

### [P2] Purple border assertion is not observable without semantics/test tags

File: `openspec/changes/enhance-track-presentation/tasks.md:103-107`

`SelectTrackBottomSheetTest` requires asserting that the current item has a purple border node. Compose UI tests do not expose `Modifier.border` color/width through the semantics tree by default, so this assertion is either impossible or will become a weak/fake test unless the implementation adds explicit `testTag`/semantics for current rows and non-current rows. Either add those test hooks to the spec/tasks or change the test to assert observable behavior/text only, with the visual border covered by manual gate 12.2.

## Notes

- The thumbnail asset exists at `feature/test/src/main/assets/track_thumbnails/chengdu_tianfu.png`.
- `layoutName` currently exists in production and tests; the planned migration covers the expected key sites and compile will catch remaining constructor/read sites.
- `NEARBY TRACKS` preservation is well specified, but use a stable baseline snippet rather than relying on shifted line numbers after the top of `LapsHomeScreen.kt` changes.

## V2 Review

Date: 2026-05-01

### Result

Changes requested.

The Records tab expansion and `3.260 km` correction are directionally right, and `openspec validate enhance-track-presentation --strict` still passes. Two text-level mismatches remain.

### [P2] CHANGE TRACK spec still bans any placeholder toast in production

File: `openspec/changes/enhance-track-presentation/specs/track-presentation/spec.md:175`

The tasks now correctly scope the grep gate to the exact removed string `"Track selection — placeholder for future round"`, while preserving START LAP SESSION's `"Lap session entry — placeholder for future round"` toast. The spec still says the button must not leave "任何 placeholder toast" in production code, which can be read as banning the preserved START LAP SESSION placeholder. Narrow this spec sentence to the CHANGE TRACK button/string, matching tasks §9.4.

### [P2] PresetTrackAssetTest path should use repo-root resolution, not `src/main/assets`

File: `openspec/changes/enhance-track-presentation/tasks.md:26`

Task 2.3 tells a JVM unit test to use `File("src/main/assets/track_thumbnails/chengdu_tianfu.png")`. Existing tests in this module resolve assets via a `projectRoot()` helper and paths like `feature/test/src/main/assets/...`, because test working directories/class locations vary between Gradle tasks and IDE runs. Make this task use the same repo-root helper pattern; otherwise the asset existence test can pass/fail depending on the runner working directory instead of the actual asset.
