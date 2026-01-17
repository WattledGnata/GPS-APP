package com.race.gps.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.race.gps.data.model.BluetoothDeviceModel

class BluetoothDeviceRepository(private val context: Context) {
    private val sharedPreferences: SharedPreferences = 
        context.getSharedPreferences("bluetooth_devices", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val deviceListType = object : TypeToken<List<BluetoothDeviceModel>>() {}.type

    fun saveDevices(devices: List<BluetoothDeviceModel>) {
        val devicesJson = gson.toJson(devices)
        sharedPreferences.edit().putString("saved_devices", devicesJson).apply()
    }

    fun getSavedDevices(): List<BluetoothDeviceModel> {
        val devicesJson = sharedPreferences.getString("saved_devices", "[]")
        return gson.fromJson(devicesJson, deviceListType)
    }

    fun addDevice(device: BluetoothDeviceModel) {
        val devices = getSavedDevices().toMutableList()
        if (!devices.any { it.address == device.address }) {
            devices.add(device)
            saveDevices(devices)
        }
    }

    fun removeDevice(address: String) {
        val devices = getSavedDevices().toMutableList()
        devices.removeAll { it.address == address }
        saveDevices(devices)
    }
}
