package com.alexkuklin.btbmsmonitor

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import java.util.UUID

data class Device(val address: String, val name: String?, val rssi: Int, val looksLikeJbd: Boolean)

/** Thin wrapper over Android GATT for the JBD BMS UART-style service (FF00 / FF01 notify / FF02 write). */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
class BleClient(private val ctx: Context, private val listener: Listener) {

    interface Listener {
        fun onScanResult(device: Device)
        fun onScanFailed(code: Int)
        fun onConnected()
        fun onDisconnected()
        fun onError(message: String)
        fun onData(bytes: ByteArray)
    }

    companion object {
        val SERVICE: UUID = UUID.fromString("0000ff00-0000-1000-8000-00805f9b34fb")
        val NOTIFY: UUID = UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb")
        val WRITE: UUID = UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val adapter: BluetoothAdapter? = ctx.getSystemService(BluetoothManager::class.java)?.adapter
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null

    val isEnabled get() = adapter?.isEnabled == true

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val jbd = result.scanRecord?.serviceUuids?.any { it.uuid == SERVICE } == true
            listener.onScanResult(
                Device(result.device.address, result.device.name ?: result.scanRecord?.deviceName, result.rssi, jbd)
            )
        }

        override fun onScanFailed(errorCode: Int) = listener.onScanFailed(errorCode)
    }

    fun startScan() {
        adapter?.bluetoothLeScanner?.startScan(scanCallback)
    }

    fun stopScan() {
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    fun connect(address: String) {
        close()
        val device = adapter?.getRemoteDevice(address) ?: return listener.onError("Bluetooth unavailable")
        gatt = device.connectGatt(ctx, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun close() {
        val g = gatt ?: return
        gatt = null
        writeChar = null
        runCatching { g.disconnect() }
        runCatching { g.close() }
    }

    fun send(data: ByteArray) {
        val g = gatt ?: return
        val c = writeChar ?: return
        val type = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, data, type)
        } else {
            c.writeType = type
            c.value = data
            g.writeCharacteristic(c)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (g !== gatt) return
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> g.discoverServices()
                BluetoothProfile.STATE_DISCONNECTED -> {
                    gatt = null
                    writeChar = null
                    g.close()
                    listener.onDisconnected()
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (g !== gatt) return
            val service = g.getService(SERVICE)
            val notify = service?.getCharacteristic(NOTIFY)
            val write = service?.getCharacteristic(WRITE)
            if (notify == null || write == null) {
                listener.onError("Not a JBD BMS (service FF00 not found)")
                close()
                listener.onDisconnected()
                return
            }
            writeChar = write
            g.setCharacteristicNotification(notify, true)
            val cccd = notify.getDescriptor(CCCD)
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(cccd)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (g === gatt) listener.onConnected()
        }

        // On API 33+ the framework's default 3-arg variant fills in c.value and forwards here.
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (g === gatt && c.uuid == NOTIFY) c.value?.let { listener.onData(it.copyOf()) }
        }
    }
}
