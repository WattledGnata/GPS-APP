package com.blazepush.core.bluetooth

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.content.Context
import android.util.Log
import com.blazepush.core.domain.model.BatteryCapabilityState
import com.blazepush.core.domain.model.BleHandshakeStage
import com.blazepush.core.domain.model.ConnectionState
import com.blazepush.core.domain.model.GpsChannelSubscriptionState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import java.util.UUID

class BleConnectionHandshakeTest {
    private val gpsServiceUuid = UUID.fromString("00001ff8-0000-1000-8000-00805f9b34fb")
    private val mainUuid = UUID.fromString("00000003-0000-1000-8000-00805f9b34fb")
    private val timeUuid = UUID.fromString("00000004-0000-1000-8000-00805f9b34fb")
    private val batteryServiceUuid = UUID.fromString("0000180F-0000-1000-8000-00805F9B34FB")
    private val batteryUuid = UUID.fromString("00002A19-0000-1000-8000-00805F9B34FB")
    private val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    private lateinit var logMock: AutoCloseable
    private lateinit var connection: BleConnection
    private lateinit var gatt: BluetoothGatt
    private lateinit var gpsService: BluetoothGattService
    private lateinit var main: BluetoothGattCharacteristic
    private lateinit var time: BluetoothGattCharacteristic
    private lateinit var mainCccd: BluetoothGattDescriptor
    private lateinit var timeCccd: BluetoothGattDescriptor

    @Before
    fun setUp() {
        logMock = Mockito.mockStatic(Log::class.java)
        connection = BleConnection(
            context = Mockito.mock(Context::class.java),
            deviceAddress = "AA:BB:CC:DD:EE:FF",
            connectionGeneration = 7L,
            onDataReceived = { _, _ -> },
        )
        gatt = Mockito.mock(BluetoothGatt::class.java)
        gpsService = Mockito.mock(BluetoothGattService::class.java)
        main = characteristic(mainUuid)
        time = characteristic(timeUuid)
        mainCccd = descriptor(main)
        timeCccd = descriptor(time)
        Mockito.`when`(gatt.getService(gpsServiceUuid)).thenReturn(gpsService)
        Mockito.`when`(gpsService.getCharacteristic(mainUuid)).thenReturn(main)
        Mockito.`when`(gpsService.getCharacteristic(timeUuid)).thenReturn(time)
        Mockito.`when`(main.getDescriptor(cccdUuid)).thenReturn(mainCccd)
        Mockito.`when`(time.getDescriptor(cccdUuid)).thenReturn(timeCccd)
        Mockito.`when`(gatt.setCharacteristicNotification(Mockito.any(), Mockito.eq(true)))
            .thenReturn(true)
        Mockito.`when`(gatt.writeDescriptor(Mockito.any())).thenReturn(true)
    }

    @After
    fun tearDown() {
        logMock.close()
    }

    @Test
    fun `Main Time and Battery CCCD are strictly serialized and observable`() {
        val batteryService = Mockito.mock(BluetoothGattService::class.java)
        val battery = characteristic(batteryUuid)
        val batteryCccd = descriptor(battery)
        Mockito.`when`(gatt.getService(batteryServiceUuid)).thenReturn(batteryService)
        Mockito.`when`(batteryService.getCharacteristic(batteryUuid)).thenReturn(battery)
        Mockito.`when`(battery.properties).thenReturn(BluetoothGattCharacteristic.PROPERTY_NOTIFY)
        Mockito.`when`(battery.getDescriptor(cccdUuid)).thenReturn(batteryCccd)
        Mockito.`when`(gatt.readCharacteristic(battery)).thenReturn(true)

        startHandshake()
        Mockito.verify(gatt).writeDescriptor(mainCccd)
        Mockito.verify(gatt, Mockito.never()).writeDescriptor(timeCccd)
        Mockito.verify(gatt, Mockito.never()).writeDescriptor(batteryCccd)

        callback().onDescriptorWrite(gatt, mainCccd, BluetoothGatt.GATT_SUCCESS)
        Mockito.verify(gatt).writeDescriptor(timeCccd)
        Mockito.verify(gatt, Mockito.never()).writeDescriptor(batteryCccd)

        callback().onDescriptorWrite(gatt, timeCccd, BluetoothGatt.GATT_SUCCESS)
        Mockito.verify(gatt).writeDescriptor(batteryCccd)
        assertEquals(BatteryCapabilityState.Pending, connection.batteryCapability.value)
        assertEquals(
            "Battery Pending must not block Main/Time channel readiness",
            ConnectionState.CONNECTED,
            connection.connectionState.value,
        )

        callback().onDescriptorWrite(gatt, batteryCccd, BluetoothGatt.GATT_SUCCESS)
        callback().onCharacteristicRead(
            gatt,
            battery,
            byteArrayOf(85.toByte()),
            BluetoothGatt.GATT_SUCCESS,
        )

        val state = connection.handshakeState.value
        assertEquals(7L, state.connectionGeneration)
        assertEquals(BleHandshakeStage.COMPLETE, state.stage)
        assertEquals(GpsChannelSubscriptionState.SUBSCRIBED, state.main)
        assertEquals(GpsChannelSubscriptionState.SUBSCRIBED, state.time)
        assertEquals(BatteryCapabilityState.Available(85), connection.batteryCapability.value)
    }

    @Test
    fun `missing Battery service is Unsupported without failing GPS handshake`() {
        Mockito.`when`(gatt.getService(batteryServiceUuid)).thenReturn(null)
        startHandshake()
        callback().onDescriptorWrite(gatt, mainCccd, BluetoothGatt.GATT_SUCCESS)
        callback().onDescriptorWrite(gatt, timeCccd, BluetoothGatt.GATT_SUCCESS)

        assertEquals(BatteryCapabilityState.Unsupported, connection.batteryCapability.value)
        assertEquals(BleHandshakeStage.COMPLETE, connection.handshakeState.value.stage)
    }

    @Test
    fun `Battery subscription failure is Failed without failing GPS handshake`() {
        val batteryService = Mockito.mock(BluetoothGattService::class.java)
        val battery = characteristic(batteryUuid)
        Mockito.`when`(gatt.getService(batteryServiceUuid)).thenReturn(batteryService)
        Mockito.`when`(batteryService.getCharacteristic(batteryUuid)).thenReturn(battery)
        Mockito.`when`(battery.properties).thenReturn(BluetoothGattCharacteristic.PROPERTY_NOTIFY)
        Mockito.`when`(gatt.setCharacteristicNotification(battery, true)).thenReturn(false)

        startHandshake()
        callback().onDescriptorWrite(gatt, mainCccd, BluetoothGatt.GATT_SUCCESS)
        callback().onDescriptorWrite(gatt, timeCccd, BluetoothGatt.GATT_SUCCESS)

        assertEquals(BatteryCapabilityState.Failed, connection.batteryCapability.value)
        assertEquals(BleHandshakeStage.COMPLETE, connection.handshakeState.value.stage)
    }

    @Test
    fun `Battery read failure is explicit Failed`() {
        val batteryService = Mockito.mock(BluetoothGattService::class.java)
        val battery = characteristic(batteryUuid)
        Mockito.`when`(gatt.getService(batteryServiceUuid)).thenReturn(batteryService)
        Mockito.`when`(batteryService.getCharacteristic(batteryUuid)).thenReturn(battery)
        Mockito.`when`(battery.properties).thenReturn(BluetoothGattCharacteristic.PROPERTY_READ)
        Mockito.`when`(gatt.readCharacteristic(battery)).thenReturn(false)

        startHandshake()
        callback().onDescriptorWrite(gatt, mainCccd, BluetoothGatt.GATT_SUCCESS)
        callback().onDescriptorWrite(gatt, timeCccd, BluetoothGatt.GATT_SUCCESS)

        assertEquals(BatteryCapabilityState.Failed, connection.batteryCapability.value)
        assertEquals(BleHandshakeStage.COMPLETE, connection.handshakeState.value.stage)
    }

    @Test
    fun `mandatory Main CCCD failure fails handshake before Time or Battery compete`() {
        startHandshake()

        callback().onDescriptorWrite(gatt, mainCccd, BluetoothGatt.GATT_FAILURE)

        val state = connection.handshakeState.value
        assertEquals(BleHandshakeStage.FAILED, state.stage)
        assertEquals(GpsChannelSubscriptionState.FAILED, state.main)
        assertEquals(GpsChannelSubscriptionState.PENDING, state.time)
        Mockito.verify(gatt, Mockito.never()).writeDescriptor(timeCccd)
        Mockito.verify(gatt, Mockito.never()).getService(batteryServiceUuid)
    }

    private fun startHandshake() {
        val method = BleConnection::class.java.getDeclaredMethod(
            "enableNotificationsSequentially",
            BluetoothGatt::class.java,
        )
        method.isAccessible = true
        method.invoke(connection, gatt)
    }

    private fun callback(): BluetoothGattCallback {
        val field = BleConnection::class.java.getDeclaredField("gattCallback")
        field.isAccessible = true
        return field.get(connection) as BluetoothGattCallback
    }

    private fun characteristic(uuid: UUID): BluetoothGattCharacteristic =
        Mockito.mock(BluetoothGattCharacteristic::class.java).also {
            Mockito.`when`(it.uuid).thenReturn(uuid)
        }

    private fun descriptor(
        characteristic: BluetoothGattCharacteristic,
    ): BluetoothGattDescriptor = Mockito.mock(BluetoothGattDescriptor::class.java).also {
        Mockito.`when`(it.characteristic).thenReturn(characteristic)
    }
}
