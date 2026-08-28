# Review: add-lap-session-phase1 Artifacts

Date: 2026-05-01
Change: `add-lap-session-phase1`
Scope: OpenSpec artifacts review before `/opsx:apply`

## Result

Changes requested.

`openspec validate add-lap-session-phase1 --strict` passes, but several requirements/tasks conflict with the current codebase and with the just-approved `enhance-track-presentation` change.

## Findings

### [P1] Laps/Records tasks conflict with `enhance-track-presentation`

Files:
- `openspec/changes/add-lap-session-phase1/design.md:241-244`
- `openspec/changes/add-lap-session-phase1/design.md:287-308`
- `openspec/changes/add-lap-session-phase1/tasks.md:213-234`

`enhance-track-presentation` has already made Track selection and thumbnail rendering part of the Laps/Records track presentation contract: Laps consumes `currentSelectedTrack`, `CHANGE TRACK` opens the real sheet, Records LAPS uses TFIC data and `TrackThumbnail`. This new change still says `CHANGE TRACK` remains a placeholder toast, `CurrentTrackPanel.trackName` should be read from a hardcoded/default catalog lookup, and Records Current Track Record remains placeholder. Applying this after enhance would either revert that work or create two sources of truth for the selected track. Make this change explicitly depend on enhance and rewrite the tasks to use `currentSelectedTrack` / `selectTrack(...)` / `TrackThumbnail`, preserving the real sheet and Records track data/asset wiring.

### [P1] End-session flow needs a real save result, not private async `endActiveLapSession()`

Files:
- `openspec/changes/add-lap-session-phase1/design.md:251-263`
- `openspec/changes/add-lap-session-phase1/tasks.md:193-204`

The proposed flow calls `sessionViewModel.endActiveLapSession()` and then shows a Snackbar with `savedSessionId` / lap count / View Record action. In current code `endActiveLapSession()` is private, returns `Unit`, clears `activeLapSessionId`, and launches `telemetryRepository.endSession(sessionId)` asynchronously. There is no reliable `savedSessionId` or completion ack for the Snackbar action, and `LapLiveScreen` cannot call the private method. Add a task to expose a public suspend/awaitable finish API such as `finishActiveLapSession(): LapSessionSaveResult?` that captures `sessionId`, lap count, awaits repository end, and returns data for the Snackbar/detail route.

### [P1] Detail screen tasks reference private repository internals

File: `openspec/changes/add-lap-session-phase1/tasks.md:139-145`

The task snippet uses `telemetryRepository.crossingDao.queryBySessionId(sessionId)`, but `TelemetryRepository.crossingDao` is a private constructor dependency. The same section says “可能需要” exposing `queryCrossings`, leaving apply to choose mid-flight. Since detail screen true-data loading is a core requirement, tasks should explicitly add public repository APIs (`getCrossings(sessionId)`, `getRecentLapSessions(...)`, maybe `readLapSamplesForSession(...)`) or explicitly inject DAOs in UI. Prefer repository APIs to avoid UI depending on DAO/entity details.

### [P1] currentLapNumber rule is internally contradictory

File: `openspec/changes/add-lap-session-phase1/specs/lap-live-state-derivation/spec.md:114-117`

The spec says `currentLapNumber = accepted start/finish crossingEvents count + 1`, but the same sentence says the first accepted start/finish means lap 1 is in progress. With one accepted start/finish, the formula returns 2, while the prose expects 1. Implementing the formula literally will show `LAP 2` immediately after the first opening crossing. Define it from `LapSession.currentLapIndex` or use a precise formula such as `max(1, acceptedStartFinishCount)` for the current lap in progress, then add explicit test assertions for first crossing, first completed lap, and Nth lap.

### [P2] Activity destroy cleanup is required but not planned

File: `openspec/changes/add-lap-session-phase1/specs/lap-session-recorder-lifecycle/spec.md:49-63`

The spec requires Activity true-destroy cleanup, but tasks do not include any implementation step for `MainActivity.onDestroy`, `ViewModel.onCleared`, or a repository-level abnormal close. Current `endActiveLapSession()` uses `viewModelScope.launch`, which is unsafe from `onCleared` because the scope is being cancelled. Either downgrade this to an explicit follow-up/non-goal for phase 1, or add concrete tasks for a non-cancelled cleanup path and tests/manual verification.

## V2 Review

Date: 2026-05-01

### Result

Changes requested.

The dependency on `enhance-track-presentation`, public finish API, repository API boundary, current lap number rule, and Activity cleanup scope are mostly fixed. There are still stale snippets that would mislead implementation.

### [P1] finishActiveLapSession sample code uses nonexistent LapRecord fields

File: `openspec/changes/add-lap-session-phase1/design.md:330-353`

The D12 sample implementation uses `session?.lapRecords`, `it.isValid`, and `it.lapTimeMs`, but the current `LapSession` model has `completedLaps: List<LapRecord>`, and `LapRecord` has `durationMillis` plus `qualityFlags`, not `isValid` / `lapTimeMs`. This is easy to copy into apply and will not compile. Rewrite the sample to use current model fields, e.g. `session?.completedLaps.orEmpty()` and `durationMillis`, and define how validity maps from `qualityFlags` or simply count completed laps if no invalid completed lap model exists.

### [P1] Snackbar specs still call private async endActiveLapSession

Files:
- `openspec/changes/add-lap-session-phase1/specs/lap-live-session-screen/spec.md:102-108`
- `openspec/changes/add-lap-session-phase1/specs/lap-session-detail-screen/spec.md:187-195`

The new D12 contract says live end must call public suspend `finishActiveLapSession()` and use `LapSessionSaveResult`. These older specs still require `sessionViewModel.endActiveLapSession()` plus direct `TelemetryRepository.endSession(sessionId)`. That reintroduces the bug fixed in P1-2: no saved session id / no await / private API. Update these requirements to reference `finishActiveLapSession()` and the returned `LapSessionSaveResult`.

### [P2] Activity destroy follow-up still has implementation language in design risks

File: `openspec/changes/add-lap-session-phase1/design.md:121-126`
Also: `design.md:473`

The spec now explicitly says Activity true-destroy cleanup is follow-up and this round must not add `onDestroy` / `onCleared` cleanup. Design D2 and Risks still say `MainActivity.onDestroy` calls `endActiveLapSession()` as mitigation. This conflicts with the new scope. Replace those lines with "not guaranteed in phase 1; follow-up backlog" wording, matching the spec.

### [P2] HOLD TO END Snackbar flow waits before returning home

File: `openspec/changes/add-lap-session-phase1/tasks.md:152-169`

The task snippet calls `snackbarHostState.showSnackbar(...)` first and only calls `navController.popBackStack()` in the non-action branch after `showSnackbar` returns. With `SnackbarDuration.Long`, that leaves the user on the ended live screen for the whole Snackbar duration unless they tap `View Record`. This contradicts the detail-screen spec's default behavior of returning home after save. Pop back to home immediately after a non-null `LapSessionSaveResult`, then show the Snackbar from the shell scope so the action can still navigate to `lap_session_detail/{sessionId}`.

### [P2] Impact and commit template still say data layer is unchanged

Files:
- `openspec/changes/add-lap-session-phase1/proposal.md:129-132`
- `openspec/changes/add-lap-session-phase1/tasks.md:424-430`

The revised design adds public `TelemetryRepository.getCrossings(...)` and `getRecentLapSessions(...)`, which changes `core/data`. Proposal Impact still says `core/*` is unaffected, and the commit body template still lists "数据层" under zero-change areas. This will make the apply/review checklist self-contradictory. Move `core/data/.../TelemetryRepository.kt` into Impact and remove `core/* / 数据层` from zero-change wording while keeping `core:domain` / BLE / simulator as unaffected if true.

## V3 Review

Date: 2026-05-01

### Result

Changes requested.

The six V2 findings are mostly fixed: the finish API now uses real `completedLaps` fields, the live/detail specs reference `finishActiveLapSession`, Activity destroy cleanup is scoped to follow-up in most places, the tasks use shell-level Snackbar, and `core/data` is included in Impact. One stale design section still conflicts with the corrected Snackbar flow.

### [P1] D7 still documents the old blocking Snackbar flow

File: `openspec/changes/add-lap-session-phase1/design.md:266-296`

Tasks and specs now require: `finishActiveLapSession()` returns, `LapLiveScreen` emits a shell-level save event, immediately `popBackStack()` to home, then `TrackTechAppShell` shows the Snackbar and handles `View Record`. Design D7 still shows `LapLiveScreen` calling `snackbarHostState.showSnackbar(...)` first and only popping after the Snackbar returns, and it still says `LapLiveScreen` receives `SnackbarHostState` via params/CompositionLocal/shared ViewModel. That is the exact blocking flow V2 rejected. Rewrite D7 to match the bus/Shell collector design (`LapSessionSaveBus` or TrackTechEventBus event), and state that `LapLiveScreen` must not hold `SnackbarHostState`.

## V4 Review

Date: 2026-05-01

### Result

Changes requested.

D7 and the detail spec now use the non-blocking Shell-level bus flow, and strict validation passes. One remaining spec line still permits the old parameter-passing shape.

### [P2] Shell spec still allows passing SnackbarHostState to LapLiveScreen

File: `openspec/changes/add-lap-session-phase1/specs/track-tech-app-shell/spec.md:38-40`

D7 now explicitly says `LapLiveScreen` must not hold `SnackbarHostState`; tasks §5.2 also says `LapLiveScreen` does not receive `snackbarHostState`. The shell spec still says the Shell-created `snackbarHostState` may be "传给 LapLiveScreen 或通过 EventBus / 共享 ViewModel 触发". That gives apply permission to reintroduce the old shape. Remove the "传给 LapLiveScreen" option and require Shell-level collection via `LapSessionSaveBus` / `TrackTechEventBus` event / equivalent shell-level state only.
