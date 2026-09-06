package com.example.miband5.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import java.util.UUID

/**
 * Phase 1+2+3 BLE layer: scan -> connect -> service discovery -> auth handshake,
 * then exposes read/write/notify passthroughs used by SyncCoordinator.
 *
 * Uses Android's native BluetoothLeScanner / BluetoothGatt (no Web Bluetooth).
 * Auto-reconnects when the app returns to foreground / adapter comes back on.
 */
@SuppressLint("MissingPermission") // callers must have granted BLUETOOTH_SCAN/CONNECT first
class BleConnection(
    private val context: Context,
    private val authKey: ByteArray?,
    private val onState: (BleConnectionState) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    private var gatt: BluetoothGatt? = null
    private var authCharacteristic: BluetoothGattCharacteristic? = null
    private var scanning = false
    private var shouldAutoReconnect = true

    // ---- Sync hooks (set by SyncCoordinator after auth) ----
    var notifyListener: ((BluetoothGattCharacteristic, ByteArray) -> Unit)? = null
    var writeListener: ((BluetoothGattCharacteristic, Int) -> Unit)? = null
    var onAuthenticated: (() -> Unit)? = null

    // ---- Auth handshake state ----
    private var handshakeStep = 0
    private val authFlags = byteArrayOf(MiBand5Auth.FLAG_LEGACY, MiBand5Auth.FLAG_BAND5)
    private var authFlagIndex = 0

    private var pendingReadCont: CancellableContinuation<ByteArray?>? = null

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = device.name ?: return
            val hasHuamiService = result.scanRecord?.serviceUuids?.any {
                it.uuid == MiBand5Gatt.UUID_SERVICE_MIBAND ||
                    it.uuid == MiBand5Gatt.UUID_SERVICE_MIBAND2
            } == true
            if (hasHuamiService ||
                (name.contains("Mi", ignoreCase = true) && name.contains("Band", ignoreCase = true))
            ) {
                stopScan()
                onState(BleConnectionState.Found(name, device.address))
                connect(device)
            }
        }
    }

    fun startScan() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            onState(BleConnectionState.Error("Bluetooth is off"))
            return
        }
        if (scanning) return
        scanning = true
        onState(BleConnectionState.Scanning)
        bluetoothAdapter.bluetoothLeScanner?.startScan(scanCallback)
            ?: onState(BleConnectionState.Error("BLE scanner unavailable"))
    }

    fun stopScan() {
        if (!scanning) return
        scanning = false
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
    }

    /** Direct connect by MAC address (used by the foreground service). */
    fun connectTo(address: String) {
        stopScan()
        val device = bluetoothAdapter?.getRemoteDevice(address)
        if (device == null) {
            onState(BleConnectionState.Error("Unknown device $address"))
            return
        }
        onState(BleConnectionState.Connecting)
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun connect(device: BluetoothDevice) {
        stopScan()
        onState(BleConnectionState.Connecting)
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    onState(BleConnectionState.DiscoveringServices)
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    this@BleConnection.gatt?.close()
                    this@BleConnection.gatt = null
                    onState(BleConnectionState.Disconnected("connection lost"))
                    if (shouldAutoReconnect) {
                        mainHandler.postDelayed({ startScan() }, 3000)
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onState(BleConnectionState.Error("service discovery failed"))
                gatt.disconnect()
                return
            }
            val service = gatt.getService(MiBand5Gatt.UUID_SERVICE_MIBAND2)
                ?: gatt.getService(MiBand5Gatt.UUID_SERVICE_MIBAND)
            val auth = service?.getCharacteristic(MiBand5Gatt.UUID_CHARACTERISTIC_AUTH)
            if (auth == null) {
                onState(BleConnectionState.Error("auth characteristic not found"))
                gatt.disconnect()
                return
            }
            authCharacteristic = auth

            // The band only sends auth-response notifications if we've enabled
            // them on this characteristic's CCCD. Without this the handshake
            // writes go out fine but no response ever arrives (silent hang).
            val notifyEnabled = gatt.setCharacteristicNotification(auth, true)
            val cccd = auth.getDescriptor(MiBand5Gatt.UUID_DESCRIPTOR_CCCD)
            if (!notifyEnabled || cccd == null) {
                onState(BleConnectionState.Error("could not enable auth notifications"))
                gatt.disconnect()
                return
            }
            onState(BleConnectionState.Authenticating)
            cccd.value = android.bluetooth.BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            // Handshake starts from onDescriptorWrite once this completes —
            // writing the handshake frame here too would collide with this
            // still-pending GATT operation (Android allows only one at a time).
            gatt.writeDescriptor(cccd)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: android.bluetooth.BluetoothGattDescriptor,
            status: Int
        ) {
            if (descriptor.uuid == MiBand5Gatt.UUID_DESCRIPTOR_CCCD &&
                descriptor.characteristic?.uuid == MiBand5Gatt.UUID_CHARACTERISTIC_AUTH
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    startHandshake()
                } else {
                    onState(BleConnectionState.Error("could not enable auth notifications"))
                    gatt.disconnect()
                }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            writeListener?.invoke(characteristic, status)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                pendingReadCont?.resume(characteristic.value)
            } else {
                pendingReadCont?.resume(null)
            }
            pendingReadCont = null
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            when (characteristic.uuid) {
                MiBand5Gatt.UUID_CHARACTERISTIC_AUTH -> handleAuthNotification(characteristic.value)
                else -> notifyListener?.invoke(characteristic, characteristic.value)
            }
        }
    }

    // ---- Auth handshake ----

    private fun startHandshake() {
        val key = authKey
        if (key == null) {
            onState(BleConnectionState.Error(
                "Auth key required. Paste the 32-hex-char key from Zepp/Gadgetbridge/huami-token."
            ))
            return
        }
        handshakeStep = 1
        writeAuth(MiBand5Auth.sendKeyFrame(key))
    }

    private fun writeAuth(bytes: ByteArray) {
        val ch = authCharacteristic ?: return
        ch.value = bytes
        // Band 5 auth characteristic accepts write-without-response only
        // (verified: https://blog.shravanrevanna.me/...).
        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        gatt?.writeCharacteristic(ch)
    }

    private fun handleAuthNotification(data: ByteArray) {
        when (val resp = MiBand5Auth.parseAuthResponse(data)) {
            is MiBand5Auth.AuthResponse.KeyRejected -> {
                onState(BleConnectionState.Error("Auth key rejected by band"))
            }
            is MiBand5Auth.AuthResponse.KeyAccepted -> {
                // Step 2: the key was accepted — now request the random
                // challenge. This was previously never sent, so the handshake
                // would stall here forever waiting for a notification that
                // was never going to arrive.
                handshakeStep = 2
                writeAuth(MiBand5Auth.requestRandomFrame())
            }
            is MiBand5Auth.AuthResponse.RandomReceived -> {
                val key = authKey ?: return
                handshakeStep = 3
                writeAuth(
                    MiBand5Auth.sendEncryptedRandomFrame(key, resp.random, authFlags[authFlagIndex])
                )
            }
            is MiBand5Auth.AuthResponse.Authenticated -> {
                handshakeStep = 0
                authFlagIndex = 0
                onState(BleConnectionState.Connected)
                onAuthenticated?.invoke()
            }
            is MiBand5Auth.AuthResponse.AuthFailed -> {
                // Retry the whole handshake with the alternate auth flag
                // (0x08 legacy vs 0x00 Band 5 firmware).
                if (authFlagIndex < authFlags.lastIndex) {
                    authFlagIndex++
                    startHandshake()
                } else {
                    onState(BleConnectionState.Error("Authentication failed"))
                }
            }
            else -> { /* Unknown — wait for next notification */ }
        }
    }

    // ---- Passthroughs used by SyncCoordinator ----

    fun readCharacteristic(serviceUuid: UUID, charUuid: UUID): Boolean {
        val ch = gatt?.getService(serviceUuid)?.getCharacteristic(charUuid) ?: return false
        return gatt?.readCharacteristic(ch) ?: false
    }

    fun writeCharacteristic(
        serviceUuid: UUID,
        charUuid: UUID,
        bytes: ByteArray,
        writeType: Int = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
    ): Boolean {
        val ch = gatt?.getService(serviceUuid)?.getCharacteristic(charUuid) ?: return false
        ch.value = bytes
        ch.writeType = writeType
        return gatt?.writeCharacteristic(ch) ?: false
    }

    fun enableNotifications(serviceUuid: UUID, charUuid: UUID): Boolean {
        val ch = gatt?.getService(serviceUuid)?.getCharacteristic(charUuid) ?: return false
        if (!(gatt?.setCharacteristicNotification(ch, true) ?: false)) return false
        val cccd = ch.getDescriptor(MiBand5Gatt.UUID_DESCRIPTOR_CCCD) ?: return false
        cccd.value = byteArrayOf(0x01, 0x00) // notify
        return gatt?.writeDescriptor(cccd) ?: false
    }

    /** One-shot suspend read; returns null on failure/timeout. */
    suspend fun readCharacteristicSuspend(serviceUuid: UUID, charUuid: UUID): ByteArray? =
        suspendCancellableCoroutine { cont ->
            val ok = readCharacteristic(serviceUuid, charUuid)
            if (!ok) {
                cont.resume(null)
            } else {
                pendingReadCont = cont
            }
        }

    fun disconnect() {
        shouldAutoReconnect = false
        stopScan()
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        onState(BleConnectionState.Idle)
    }
}
