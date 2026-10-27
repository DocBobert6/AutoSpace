package app.autospace.car

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build

/** One Bluetooth device as the Controllers screen shows it. */
data class ControllerDevice(
    val name: String,
    val address: String,
    val bonded: Boolean,
    val looksLikeController: Boolean,
)

/**
 * Name-based controller heuristic, pure so tests pin it. Most gamepads
 * advertise a telling name (Xbox, DualSense, 8BitDo, "Controller"); a device
 * with no telling name is listed anyway, just without the badge, so nothing
 * a driver owns is ever hidden by a guess.
 */
fun looksLikeController(name: String?): Boolean {
    if (name.isNullOrBlank()) return false
    val lower = name.lowercase()
    return listOf(
        "controller",
        "gamepad",
        "joystick",
        "joy-con",
        "xbox",
        "playstation",
        "dualshock",
        "dualsense",
        "8bitdo",
        "pro controller",
    ).any { it in lower }
}

/**
 * r89: Bluetooth game controllers for the Games menus, phone-side (AutoSpace
 * runs on the phone, so this is the phone's adapter, bonded list, and
 * discovery — the same patterns CarBluetooth already uses for the car link).
 * Round 1 covers discovery, pairing, and menu navigation only: no per-core
 * button maps, no disconnect pausing, no gameplay input of any kind.
 *
 * Pairing completes in the phone's own pairing dialog; the car screen cannot
 * confirm it, so after tapping Pair the screen says so and offers Refresh.
 * Missing permissions never crash: the screen explains that Bluetooth is
 * granted on the phone instead.
 */
object GameControllers {

    fun hasConnectPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    fun hasScanPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) ==
            PackageManager.PERMISSION_GRANTED

    private fun adapter(context: Context): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: @Suppress("DEPRECATION") BluetoothAdapter.getDefaultAdapter()

    private fun toDevice(device: BluetoothDevice): ControllerDevice {
        val name = runCatching { device.name }.getOrNull() ?: "Unknown device"
        val bonded = runCatching { device.bondState == BluetoothDevice.BOND_BONDED }.getOrDefault(false)
        return ControllerDevice(name, device.address, bonded, looksLikeController(name))
    }

    /** Already-paired devices, controller-like first, then alphabetical. */
    @SuppressLint("MissingPermission")
    fun bonded(context: Context): List<ControllerDevice> {
        if (!hasConnectPermission(context)) return emptyList()
        val adapter = adapter(context) ?: return emptyList()
        return runCatching {
            adapter.bondedDevices.map { toDevice(it) }
                .sortedWith(
                    compareByDescending<ControllerDevice> { it.looksLikeController }
                        .thenBy { it.name.lowercase() },
                )
        }.getOrDefault(emptyList())
    }

    /** One discovery run: found devices arrive via [onFound], [onFinished]
     * fires when the adapter stops scanning. Call [stop] when leaving. */
    class Discovery(
        private val context: Context,
        private val onFound: (ControllerDevice) -> Unit,
        private val onFinished: () -> Unit,
    ) {
        private var receiver: BroadcastReceiver? = null

        @SuppressLint("MissingPermission")
        fun start(): Boolean {
            if (!hasScanPermission(context)) return false
            val adapter = adapter(context) ?: return false
            if (adapter.isDiscovering) return true
            val foundAction = BluetoothDevice.ACTION_FOUND
            val finishedAction = BluetoothAdapter.ACTION_DISCOVERY_FINISHED
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    when (intent.action) {
                        foundAction -> {
                            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                            } else {
                                @Suppress("DEPRECATION")
                                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                            }
                            if (device != null) onFound(toDevice(device))
                        }
                        finishedAction -> onFinished()
                    }
                }
            }
            this.receiver = receiver
            context.registerReceiver(
                receiver,
                IntentFilter().apply {
                    addAction(foundAction)
                    addAction(finishedAction)
                },
            )
            return runCatching { adapter.startDiscovery() }.getOrDefault(false)
        }

        @SuppressLint("MissingPermission")
        fun stop() {
            receiver?.let { runCatching { context.unregisterReceiver(it) } }
            receiver = null
            runCatching { adapter(context)?.cancelDiscovery() }
        }
    }

    /** Ask the phone to bond with a device: the system pairing dialog opens
     * on the phone. False means the request could not even start. */
    @SuppressLint("MissingPermission")
    fun pair(context: Context, address: String): Boolean {
        if (!hasConnectPermission(context)) return false
        val adapter = adapter(context) ?: return false
        val device = runCatching {
            adapter.bondedDevices.firstOrNull { it.address == address }
                ?: adapter.getRemoteDevice(address)
        }.getOrNull() ?: return false
        return runCatching { device.createBond() }.getOrDefault(false)
    }
}
