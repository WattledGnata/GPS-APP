# Track Tech Function Probe

这份探针用于在正式实现 UI 前核对工程能力。
目标不是写代码，而是回答：

1. 交互稿需要哪些功能/状态/导航支撑？
2. 当前工程是否已经具备这些能力？
3. Claude Code/CC 落地前还需要重点检查哪些文件？

## Current Design Target

当前认可的方向：

- App Shell: `Test | Laps | Records | Device`
- Visual version: `Four Tabs V2 - Calmer App Shell`
- BLE scan: `BLE Scan Sheet V2 - Calmer Utility`
- Execution screens can remain higher-intensity Track Tech HUD.
- Details / settings / sheets should be lower-intensity utility UI.

Primary design docs:

- `docs/design/track-tech-ui-extraction.md`
- `docs/design/device-tab-interaction.md`
- `docs/design/visual-version-log.md`

## Required Capabilities

### 1. App Shell / Four Tabs

Required:

- Bottom navigation with four stable destinations:
  - `Test`
  - `Laps`
  - `Records`
  - `Device`
- Each tab owns a root screen and can preserve local UI state.
- Test/Laps can navigate into execution/result flows.
- Device can open scan bottom sheet and detail screens.
- Records remains usable without live GPS.

Current support:

- Partial.
- Current navigation is a linear flow in `TestFlowNavigation.kt`:
  - `Connection -> Selection -> LapDebugConfig -> Execution -> Result/History`
- There is no persistent bottom tab shell yet.
- Connection is currently a blocking first route, not a `Device` tab.

Probe verdict:

- Needs shell-level refactor.
- Existing screens can be reused, but navigation structure does not yet match the target.

### 2. Global Device/GPS State

Required:

- Shared connection state across tabs.
- Shared latest GPS data.
- Shared data quality.
- Compact status strips on Test/Laps.
- Device tab owns full explanation and repair path.

Current support:

- Strong partial support.
- `GpsDataViewModel` exposes:
  - `gpsData: StateFlow<GpsData>`
  - `connectionState: StateFlow<ConnectionState>`
  - `dataQuality: StateFlow<DataQuality>`
  - `isScanning`
  - `scanResults`
- Comment says it is a singleton and shared by pages.

Probe verdict:

- Data foundation exists.
- Need new UI state adapters / mappers for compact status labels:
  - `Ready to Test`
  - `Connect GPS Device`
  - `Waiting for GPS Lock`
  - `Signal Lost`

### 3. Device Gating From Other Tabs

Required:

- Test/Laps start actions are blocked when BLE/GPS is not ready.
- Blocked action routes user to Device tab.
- Device can auto-emphasize scan, or open scan sheet.
- Optional future return flow to previous tab after connection.

Current support:

- Partial.
- `SmartTestLauncher` already evaluates readiness for execution.
- Current selection screen can enter execution and Smart Launch, but there is no tab-level gate.
- Current app starts on `Connection` route before selection, so gating exists only as an initial screen.

Probe verdict:

- Need new shell-level behavior:
  - calculate `canStartTest`
  - intercept Test/Laps primary actions
  - switch selected tab to Device
  - optionally open scan sheet if disconnected

### 4. Device Home

Required:

- Device tab root screen as a connection console:
  - readiness hero
  - quick BLE / SATS / RATE row
  - connected device panel
  - GPS Details entry
  - Diagnostics entry
  - Settings entry
- Connected state should be calm and concise.
- Non-connected state should prioritize `Scan Devices`.

Current support:

- Partial.
- `DeviceConnectionScreen.kt` currently shows:
  - title
  - connection status card
  - scan button
  - GPS signal card
  - DataQualityCard when poor
  - start test button
- It is Material-style and linear-flow oriented.

Probe verdict:

- Existing data and actions can support Device Home.
- UI needs to be reorganized.
- The current `start test` button should not belong to Device root in the new shell.

### 5. BLE Scan / Select Device

Required:

- Bottom sheet, not a full blocking page.
- States:
  - scanning
  - devices found
  - no devices found
  - connecting
  - connection failed
  - connected but waiting GPS
- Device rows show:
  - name
  - optional recommendation/support label
  - RSSI / signal bars
  - selected state
- Actions:
  - connect
  - scan again
  - close

Current support:

- Partial.
- `DeviceScanDialog.kt` currently uses a center dialog with:
  - scanning state
  - device count
  - device list
  - RSSI and signal bars
  - stop scan
  - close
- `GpsDataViewModel` supports:
  - `startScan()`
  - `stopScan()`
  - `connectDevice(device)`
  - `scanResults`
  - `isScanning`

Probe verdict:

- Functional base exists.
- UI container should change from center `Dialog` to modal bottom sheet.
- Connecting / failed connection state may need additional explicit UI state if not already derivable.
- No obvious current selected-device state; likely needed for V2 sheet if connect is a separate button after selection.

### 6. GPS Details

Required:

- Detail screen opened from Device Home.
- Expandable sections:
  - Summary
  - Signal
  - Data Stream
  - Position
  - Device
- Reusable `MetricSection` + `MetricTile` layout so metrics can be added later.

Current support:

- Strong data support for GPS details:
  - `GpsData`: timestamp, speed, latitude, longitude, altitude, bearing, satelliteCount, hdop, vdop, frequency, isConnected, isTestReady, errorMessage, fixQuality, isTimeSynced
  - `DataQuality`: satelliteCount, signalStrength, hdop, vdop, dataAge, packetLoss, frequency, overall, overallScore
- Missing/unclear device metadata:
  - connected device name
  - connected device address
  - live RSSI after connection
  - firmware
  - protocol label can be static `RaceChrono BLE` initially

Probe verdict:

- GPS detail can be implemented meaningfully now.
- Device subsection may need fallbacks/placeholders unless connected device metadata is exposed.

### 7. Test Tab Home

Required:

- Compact GPS status strip.
- Current speed hero.
- Performance actions:
  - `0-100`
  - `100-0`
- Latest result summary.
- Blocked state when GPS/BLE unavailable.

Current support:

- Partial.
- `TestSelectionScreen.kt` currently has test selection cards, car model input, lap debug entry, history button.
- Performance execution and result flow already exist.
- Latest result summary support is unclear from the inspected files.

Probe verdict:

- Core actions exist.
- Need new home composition, compact status strip, blocked state, and latest result query/wiring.

### 8. Laps Tab Home

Required:

- Current track.
- Track preview.
- Start lap session.
- Change track.
- Recent best.
- Nearby tracks.
- Blocked state when GPS/BLE unavailable for session start.

Current support:

- Partial.
- `TestFlowNavigation.kt` has:
  - `availableTracks`
  - `lapRunConfig`
  - `latestLapRecords`
  - `lapSession`
- Existing UI is still debug-oriented:
  - `LapDebugConfigScreen`
  - `LapDebugExecutionScreen`
  - `LapDebugResultScreen`

Probe verdict:

- Domain/session foundation exists.
- Need productized Laps Home and Track Selection flow.

### 9. Records Tab

Required:

- Segmented control:
  - Performance
  - Laps
- Performance summary.
- Speed curve / acceleration curve chart.
- Recent runs list.
- Laps records grouped by track/session later.

Current support:

- Partial.
- Existing:
  - `TestHistoryScreen`
  - `TestResultScreen`
  - `SpeedChart`
  - result metrics/charts.
- Current history is not yet a first-class bottom tab and may not separate Performance/Laps as desired.

Probe verdict:

- Records can start as a shell using existing history/result data.
- Chart and segmentation need new UI work.

### 10. Track Tech UI Components

Required reusable components:

- `CutCornerPanel`
- `TrackTechBottomNav`
- `StatusStrip`
- `SectionHeader`
- `PrimaryActionPanel`
- `SecondaryActionPanel`
- `MetricNumber`
- `MetricTile`
- `MetricSection`
- `TrackMapPreview`
- `SpeedCurveChart` styling pass
- `BleScanBottomSheet`

Current support:

- Minimal.
- Existing UI is mostly Material3 `Card`, `Button`, `Dialog`, `RoundedCornerShape`.
- Existing chart component exists but likely needs styling alignment.

Probe verdict:

- Need a small design-system/component layer before replacing screens.

### 11. Fonts / Assets

Required:

- Racing italic title font.
- Seven-segment metric font.
- Icons:
  - speedometer
  - brake
  - flag
  - records/chart
  - bluetooth
  - satellite
  - signal bars
  - gear
  - help
  - chevrons
- Vector/canvas patterns:
  - cut corners
  - slashes
  - subtle grid
  - track lines

Current support:

- Some exploratory SVG assets in `docs/design/track-tech-assets/`.
- No confirmed production font assets in `res/font/` from this probe.
- Existing UI likely uses Material icons/text.

Probe verdict:

- Fonts need license decision before committing.
- Most graphics should be native vector/canvas, not bitmap cut images.

## Suggested CC Probe Prompt

Use this prompt with Claude Code before implementation:

```text
We are preparing to implement the Track Tech UI redesign.
Do not implement yet. Probe the current Android/Compose codebase and report support/gaps for docs/design/track-tech-function-probe.md.

Primary questions:
1. Can the current navigation support a persistent four-tab shell: Test, Laps, Records, Device?
2. Which existing ViewModels/state flows can drive global BLE/GPS status across all tabs?
3. Can DeviceHome, GPS Details, and BLE Scan bottom sheet be implemented with existing GpsDataViewModel/BleDeviceManager data?
4. What state is missing for selected device, connecting failure, connected device metadata, live RSSI, and auto reconnect?
5. What existing screens/components can be reused for Test, Laps, Records, and what should be replaced?
6. What reusable Track Tech components should be built first?

Please return:
- capability matrix: supported / partial / missing
- exact files/classes involved
- recommended first implementation slice
- risks and questions before coding
```

## Recommended First Implementation Slice

If the probe confirms the above, the first implementation slice should avoid doing every page at once.

Recommended slice:

1. Create reusable Track Tech primitives:
   - cut-corner panel
   - colors/tokens
   - status strip
   - bottom nav
2. Introduce App Shell with four tabs.
3. Move current connection experience into Device tab.
4. Implement DeviceHome + BLE scan bottom sheet.
5. Add gating from Test/Laps to Device when BLE/GPS is not ready.

Defer:

- Full Records redesign.
- Full Laps productized track selection.
- GPS Details dense metrics page.
- Font finalization if license is not decided.

## Initial Probe Summary

Based on a light inspection:

- The data layer is stronger than the current UI.
- BLE scan/connect and GPS quality data already exist.
- The largest gap is navigation/information architecture, not raw capability.
- The second-largest gap is Track Tech reusable UI primitives.
- Device tab is a good first landing zone because it exercises global state, scanning, readiness, and gating without touching test engine behavior.

