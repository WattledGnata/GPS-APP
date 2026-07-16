## ADDED Requirements

### Requirement: BLE Battery Service Discovery

`BleConnection` SHALL discover BLE standard Battery Service (`0x180F`) after GPS notification enablement handshake completes. Discovery MUST NOT block or delay GPS characteristic notification enablement.

`BleConnection` SHALL expose `batteryPercent: StateFlow<Int?>` where `null` indicates battery level is unavailable (service not found, characteristic not found, or not yet read).

#### Scenario: Device has Battery Service

- **WHEN** BLE services are discovered and `0x180F` service exists with `0x2A19` characteristic
- **THEN** `setupBattery()` SHALL subscribe to Notify or Indicate on `0x2A19` and read initial value
- **AND** `batteryPercent` SHALL eventually emit a non-null value in `0..100`

#### Scenario: Device has no Battery Service

- **WHEN** BLE services are discovered and `0x180F` service is absent
- **THEN** `batteryPercent` SHALL remain `null`

#### Scenario: Battery Service exists but characteristic missing

- **WHEN** `0x180F` service exists but `0x2A19` characteristic is absent
- **THEN** `batteryPercent` SHALL remain `null`

#### Scenario: Battery discovery does not block GPS handshake

- **WHEN** GPS CCCD descriptors are all written and handshake completes
- **THEN** `connectionState` SHALL transition to `CONNECTED` regardless of Battery Service discovery outcome

### Requirement: Battery Level Parsing

The system SHALL parse BLE Battery Level (`0x2A19`) characteristic values according to the Bluetooth SIG specification: first byte as unsigned integer `0..100`. Values outside this range or empty data SHALL be treated as invalid and discarded, preserving the last valid reading.

#### Scenario: Valid 85% reading

- **WHEN** receiving `byteArrayOf(0x55)` from `0x2A19`
- **THEN** `parseBatteryPercent()` SHALL return `85`

#### Scenario: Valid 0% reading

- **WHEN** receiving `byteArrayOf(0x00)` from `0x2A19`
- **THEN** `parseBatteryPercent()` SHALL return `0`

#### Scenario: Valid 100% reading

- **WHEN** receiving `byteArrayOf(0x64)` from `0x2A19`
- **THEN** `parseBatteryPercent()` SHALL return `100`

#### Scenario: Invalid value > 100

- **WHEN** receiving `byteArrayOf(0x65)` (101) from `0x2A19`
- **THEN** `parseBatteryPercent()` SHALL return `null`
- **AND** the last valid `batteryPercent` value SHALL be preserved (not updated)

#### Scenario: Empty data

- **WHEN** receiving `byteArrayOf()` from `0x2A19`
- **THEN** `parseBatteryPercent()` SHALL return `null`

#### Scenario: Null data

- **WHEN** receiving `null` from `0x2A19`
- **THEN** `parseBatteryPercent()` SHALL return `null`

### Requirement: Battery Level Notification Subscription

When `0x2A19` characteristic supports Notify or Indicate, the system SHALL enable notification/indication by writing the appropriate CCCD value. After successful CCCD write, the system SHALL read the initial battery level. When only READ property is supported, the system SHALL perform a single read without subscription.

#### Scenario: Notify supported — subscribe and read

- **WHEN** `0x2A19` has `PROPERTY_NOTIFY` flag
- **THEN** system SHALL call `setCharacteristicNotification(characteristic, true)`
- **AND** write `ENABLE_NOTIFICATION_VALUE` to CCCD
- **AND** after CCCD write success, read the characteristic once

#### Scenario: Indicate supported — subscribe and read

- **WHEN** `0x2A19` has `PROPERTY_INDICATE` flag (but not NOTIFY)
- **THEN** system SHALL call `setCharacteristicNotification(characteristic, true)`
- **AND** write `ENABLE_INDICATION_VALUE` to CCCD
- **AND** after CCCD write success, read the characteristic once

#### Scenario: No notify or indicate — read once only

- **WHEN** `0x2A19` has neither `PROPERTY_NOTIFY` nor `PROPERTY_INDICATE`
- **THEN** system SHALL call `readCharacteristic()` exactly once
- **AND** no CCCD write SHALL be attempted

#### Scenario: CCCD write fails — fallback to read

- **WHEN** `gatt.writeDescriptor(cccd)` returns `false`
- **THEN** system SHALL fallback to `readCharacteristic()`

### Requirement: Battery Data Channel

`BluetoothDataSource` SHALL proxy `BleConnection.batteryPercent` as its own `StateFlow<Int?>`. `GpsDataRepository` SHALL expose `batteryPercent` directly from `BluetoothDataSource`. `GpsDataViewModel` SHALL expose `batteryPercent` as a `StateFlow<Int?>` with `WhileSubscribed(5000)` sharing strategy and `null` initial value.

#### Scenario: Data flows from BLE to ViewModel

- **WHEN** `BleConnection._batteryPercent` emits `85`
- **THEN** `BluetoothDataSource.batteryPercent` SHALL emit `85`
- **AND** `GpsDataRepository.batteryPercent` SHALL emit `85`
- **AND** `GpsDataViewModel.batteryPercent` SHALL emit `85`

#### Scenario: null flows through channel

- **WHEN** `BleConnection._batteryPercent` emits `null`
- **THEN** `GpsDataViewModel.batteryPercent` SHALL emit `null`

#### Scenario: ViewModel initial value is null

- **WHEN** `GpsDataViewModel` is first instantiated with no active BLE connection
- **THEN** `batteryPercent.value` SHALL be `null`

### Requirement: Battery Display in ConnectedDeviceCard

`DeviceHomeScreen` SHALL display a battery indicator row within `ConnectedDeviceCard` when `connectionState == CONNECTED`. The indicator SHALL show a battery icon (mapped by percentage to one of 7 levels) plus the percentage number in Mechanical (DSEG7) font with "%" unit.

When `batteryPercent` is `null` and the device is connected (indicating no Battery Service capability), the indicator SHALL show a grey `BatteryUnknown` icon and the text "N/A".

When the device is not connected, the battery indicator SHALL NOT be displayed.

#### Scenario: Battery at 85% — full icon, white

- **WHEN** `batteryPercent = 85` and `connectionState = CONNECTED`
- **THEN** system SHALL display `Battery5Bar` icon in white
- **AND** display "85" in Mechanical Small font with "%" unit in white

#### Scenario: Battery at 15% — low bar icon, red

- **WHEN** `batteryPercent = 15` and `connectionState = CONNECTED`
- **THEN** system SHALL display `Battery2Bar` icon in `TrackTechColors.Red`
- **AND** display "15" in Mechanical Small font with "%" unit in red

#### Scenario: Battery at 0% — alert icon, red

- **WHEN** `batteryPercent = 0` and `connectionState = CONNECTED`
- **THEN** system SHALL display `BatteryAlert` icon in `TrackTechColors.Red`
- **AND** display "0" in Mechanical Small font with "%" unit in red

#### Scenario: No battery service — N/A fallback

- **WHEN** `batteryPercent = null` and `connectionState = CONNECTED`
- **THEN** system SHALL display `BatteryUnknown` icon in grey (`TrackTechColors.TextMuted`)
- **AND** display "N/A" in Score Small font in grey

#### Scenario: Disconnected — battery row hidden

- **WHEN** `connectionState = DISCONNECTED`
- **THEN** the battery indicator row SHALL NOT be rendered

#### Scenario: Connecting — battery row hidden (service not yet discovered)

- **WHEN** `connectionState = CONNECTING`
- **THEN** the battery indicator row SHALL NOT be rendered

### Requirement: Battery State Cleanup on Disconnect

When the BLE connection is terminated (user disconnect, remote disconnect, or connection timeout), `batteryPercent` SHALL be reset to `null` to prevent displaying stale battery data from a previous device.

#### Scenario: User disconnects

- **WHEN** user triggers `disconnect()`
- **THEN** `batteryPercent` SHALL be reset to `null`

#### Scenario: Remote disconnect

- **WHEN** `onConnectionStateChange` receives `STATE_DISCONNECTED`
- **THEN** `cleanup()` SHALL set `_batteryPercent.value = null`

#### Scenario: Connect new device after previous device had battery data

- **WHEN** connecting to a new GPS device after previous device reported 85%
- **THEN** `batteryPercent` SHALL be `null` until new device's services are discovered and battery is read
