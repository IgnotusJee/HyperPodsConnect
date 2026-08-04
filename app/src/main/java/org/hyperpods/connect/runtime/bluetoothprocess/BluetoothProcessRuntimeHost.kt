package org.hyperpods.connect.runtime.bluetoothprocess

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import moe.chenxy.headphones.core.device.DeviceCandidate
import moe.chenxy.headphones.core.device.DeviceId
import moe.chenxy.headphones.core.device.DeviceIdentity
import moe.chenxy.headphones.core.device.TransportKind
import moe.chenxy.headphones.core.feature.FeatureId
import moe.chenxy.headphones.core.operation.FeatureCommand
import moe.chenxy.headphones.core.operation.OperationEvent
import moe.chenxy.headphones.core.operation.OperationResult
import moe.chenxy.headphones.core.operation.RequestId
import moe.chenxy.headphones.core.session.DisconnectCause
import moe.chenxy.headphones.core.session.SessionState
import moe.chenxy.headphones.engine.DriverRegistry
import moe.chenxy.headphones.engine.HeadphoneSessionManager
import moe.chenxy.headphones.engine.HeadphoneSnapshot
import moe.chenxy.headphones.engine.SessionRuntimeHost
import moe.chenxy.headphones.transport.android.AndroidBluetoothTransportFactory
import org.hyperpods.connect.ipc.HeadphoneIpcContract
import org.hyperpods.connect.ipc.HeadphoneSnapshotPayload
import org.hyperpods.connect.ipc.IpcReplayGuard
import org.hyperpods.connect.ipc.IpcSenderPolicy
import org.hyperpods.connect.ipc.IpcCommandValidator
import org.hyperpods.connect.ipc.isSentFrom
import org.hyperpods.connect.ipc.sendIdentitySharedBroadcast
import org.hyperpods.connect.pods.HeadphoneSessionCoordinator
import org.hyperpods.connect.runtime.bluetoothprocess.driver.DefaultDriverCatalog
import org.hyperpods.connect.runtime.bluetoothprocess.driver.DriverSessionDiagnostics
import org.hyperpods.connect.BuildConfig

/**
 * The sole control-session authority in com.android.bluetooth.
 *
 * The app, MiLink and Xiaomi integrations only exchange versioned IPC with this
 * host. Re-entering an Activity merely requests the current StateFlow snapshot;
 * it never constructs another Bluetooth session.
 */
@SuppressLint("MissingPermission", "StaticFieldLeak")
object BluetoothProcessRuntimeHost : SessionRuntimeHost {
    private val initialized = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val registry = DriverRegistry()
    private val manager = HeadphoneSessionManager(registry, scope)
    private val hostInstanceId = UUID.randomUUID().toString()
    private val commandReplayGuard = IpcReplayGuard()
    private lateinit var context: Context
    private var activeDevice: BluetoothDevice? = null
    private var vendorEventJob: Job? = null
    private var vendorEventGeneration = -1L

    override val snapshot: StateFlow<HeadphoneSnapshot?> get() = manager.snapshot
    override val operations: Flow<OperationEvent> get() = manager.operations

    fun initialize(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        this.context = context.applicationContext ?: context
        this.context.registerReceiver(commandReceiver, IntentFilter().apply {
            addAction(HeadphoneIpcContract.ACTION_HEADPHONE_COMMAND)
        }, Context.RECEIVER_EXPORTED)
        scope.launch {
            manager.snapshot.collect { value ->
                value ?: return@collect
                val failure = value.connection as? SessionState.Failed
                Log.d(
                    "HyperPodsConnect-Engine",
                    "snapshot generation=${value.generationId} " +
                        "vendor=${value.profile?.vendorId?.value} " +
                        "transport=${value.profile?.transport} " +
                        "connection=${value.connection::class.simpleName.orEmpty()} compatibility=" +
                        "${value.profile?.compatibilityLevel} model=${value.profile?.model} " +
                        "firmware=${value.state.firmware ?: value.profile?.firmware} " +
                        "batteries=${value.state.batteries}" +
                        if (failure == null) {
                            ""
                        } else {
                            " failure=${failure.category}/${failure.cause}" +
                                " detail=${failure.detail?.redactBluetoothAddresses()}"
                        },
                )
                attachVendorEvents(value.generationId)
                // Notification and island effects consume the brand-neutral snapshot.
                HeadphoneSessionCoordinator.onEngineSnapshot(value)
                publishSnapshot(value)
            }
        }
        scope.launch {
            manager.operations.collect { event ->
                HeadphoneSessionCoordinator.onEngineOperation(event)
            }
        }
    }

    fun connect(
        context: Context,
        device: BluetoothDevice,
    ) {
        initialize(context)
        activeDevice = device
        registerDrivers()
        scope.launch {
            connect(
                candidate(device),
                AndroidBluetoothTransportFactory(
                    this@BluetoothProcessRuntimeHost.context,
                    device,
                    DefaultDriverCatalog.gattDeviceResolver(this@BluetoothProcessRuntimeHost.context),
                ),
            )
        }
    }

    fun connectAutomatically(
        context: Context,
        device: BluetoothDevice,
    ) {
        initialize(context)
        registerDrivers()
        val candidate = candidate(device)
        val transportFactory = AndroidBluetoothTransportFactory(
            this.context,
            device,
            DefaultDriverCatalog.gattDeviceResolver(this.context),
        )
        scope.launch {
            if (manager.autoConnect(candidate, transportFactory) != null) {
                activeDevice = device
            }
        }
    }

    fun connectFromProfile(
        context: Context,
        device: BluetoothDevice,
    ) {
        initialize(context)
        activeDevice = device
        registerDrivers()
        scope.launch {
            connect(
                candidate(device),
                AndroidBluetoothTransportFactory(
                    this@BluetoothProcessRuntimeHost.context,
                    device,
                    DefaultDriverCatalog.gattDeviceResolver(this@BluetoothProcessRuntimeHost.context),
                ),
            )
        }
    }

    fun canAutoConnect(
        context: Context,
        device: BluetoothDevice,
    ): Boolean {
        initialize(context)
        registerDrivers()
        return registry.resolve(candidate(device))?.let(DriverRegistry::canAutoConnect) == true
    }

    fun canConnectFromProfile(
        context: Context,
        device: BluetoothDevice,
    ): Boolean {
        initialize(context)
        registerDrivers()
        val candidate = candidate(device)
        return registry.resolve(candidate)?.let {
            DriverRegistry.canConnectFromProfile(it, candidate)
        } == true
    }

    private fun registerDrivers() {
        DefaultDriverCatalog.register(context, registry)
    }

    override suspend fun connect(
        candidate: DeviceCandidate,
        transportFactory: moe.chenxy.headphones.core.transport.TransportFactory,
    ): Long = manager.connect(candidate, transportFactory)

    override suspend fun refresh(featureIds: Set<FeatureId>) = manager.refresh(featureIds)

    override suspend fun execute(
        command: FeatureCommand,
        requestId: RequestId?,
    ): OperationResult = manager.execute(command, requestId)

    fun executeAsync(command: FeatureCommand, requestId: String? = null) {
        scope.launch {
            val callerRequestId = requestId?.let(::RequestId)
            val initialSnapshot = snapshot.value
            val generationId = initialSnapshot?.generationId
            val driverResult = manager.execute(command, callerRequestId)
            val result = if (callerRequestId != null && driverResult.requestId != callerRequestId) {
                Log.e(
                    ENGINE_LOG_TAG,
                    "driver returned requestId=${driverResult.requestId.value} for " +
                        "caller requestId=${callerRequestId.value}; normalizing terminal IPC event",
                )
                driverResult.copy(requestId = callerRequestId)
            } else {
                driverResult
            }
            if (requestId == null) return@launch

            val collected = generationId?.let { expectedGeneration ->
                withTimeoutOrNull(TERMINAL_SNAPSHOT_TIMEOUT_MILLIS) {
                    snapshot.filterNotNull().first { candidate ->
                        candidate.matchesTerminalOperation(expectedGeneration, result)
                    }
                }
            }
            if (collected != null) {
                publishSnapshot(collected, requestId)
                return@launch
            }

            val fallbackBase = snapshot.value
                ?.takeIf { current -> current.generationId == generationId }
                ?: initialSnapshot
            if (fallbackBase == null) {
                Log.w(
                    ENGINE_LOG_TAG,
                    "cannot publish terminal requestId=$requestId because no snapshot is available",
                )
                return@launch
            }
            val fallback = fallbackBase.withTerminalOperation(
                command = command,
                result = result,
                clockMillis = System.currentTimeMillis(),
            )
            Log.w(
                ENGINE_LOG_TAG,
                "terminal collector convergence timed out for requestId=$requestId; " +
                    "publishing result-backed phase=${result.phase}",
            )
            publishSnapshot(fallback, requestId)
        }
    }

    fun refreshAsync(featureIds: Set<FeatureId> = emptySet(), requestId: String? = null) {
        scope.launch {
            manager.refresh(featureIds)
            publishCurrentSnapshot(requestId)
        }
    }

    override suspend fun disconnect(cause: DisconnectCause) {
        manager.disconnect(cause)
        vendorEventJob?.cancel()
        vendorEventJob = null
        vendorEventGeneration = -1
        activeDevice = null
    }

    fun disconnectAsync(cause: DisconnectCause = DisconnectCause.REQUESTED) {
        scope.launch { disconnect(cause) }
    }

    suspend fun sendDebugFrame(bytes: ByteArray): Boolean {
        if (!BuildConfig.ALLOW_RAW_PROTOCOL_CONSOLE) return false
        return DriverSessionDiagnostics.sendDebugFrame(manager.activeSession(), bytes)
    }

    fun publishCurrentSnapshot(requestId: String? = null) {
        snapshot.value?.let { publishSnapshot(it, requestId) }
    }

    private fun publishSnapshot(value: HeadphoneSnapshot, requestId: String? = null) {
        if (!::context.isInitialized) return
        val payload = HeadphoneSnapshotPayload.from(value, hostInstanceId)
        HeadphoneIpcContract.eventTargets.forEach { target ->
            context.sendIdentitySharedBroadcast(
                HeadphoneIpcContract.eventIntent(
                    snapshot = payload,
                    requestId = requestId,
                    targetPackage = target,
                ),
            )
        }
    }

    private suspend fun attachVendorEvents(generationId: Long) {
        if (vendorEventGeneration == generationId) return
        vendorEventJob?.cancel()
        vendorEventGeneration = generationId
        vendorEventJob = DriverSessionDiagnostics.attach(
            context = context,
            session = manager.activeSession(),
            scope = scope,
            generationId = generationId,
            isCurrentGeneration = { snapshot.value?.generationId == generationId },
        )
    }

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!isSentFrom(IpcSenderPolicy.commandSenders)) return
            val envelope = intent?.let(HeadphoneIpcContract::decodeCommand) ?: return
            if (!commandReplayGuard.accept(envelope.requestId, envelope.timestamp)) return
            if (envelope.payload.type == HeadphoneIpcContract.TYPE_REQUEST_SNAPSHOT) {
                publishCurrentSnapshot(envelope.requestId)
                return
            }
            val current = snapshot.value
            if (!IpcCommandValidator.targetsCurrentSession(
                    envelope,
                    current?.deviceId?.value,
                    current?.profile?.vendorId?.value,
                )
            ) return
            val command = envelope.payload.toFeatureCommand() ?: return
            if (command is FeatureCommand.RefreshAll) {
                refreshAsync(requestId = envelope.requestId)
            } else if (command is FeatureCommand.Refresh) {
                refreshAsync(setOf(command.featureId), envelope.requestId)
            } else {
                executeAsync(command, envelope.requestId)
            }
        }
    }

    private fun candidate(device: BluetoothDevice): DeviceCandidate {
        val advertisedUuids = device.uuids?.map { it.uuid.toString() }?.toSet().orEmpty()
        val identity = DeviceIdentity(
            id = DeviceId.fromAddress(device.address),
            vendorId = DefaultDriverCatalog.inferVendor(device.name, advertisedUuids),
            primaryAddress = device.address,
        )
        return DeviceCandidate(
            identity = identity,
            displayName = device.name,
            bonded = device.bondState == BluetoothDevice.BOND_BONDED,
            advertisedUuids = advertisedUuids,
            availableTransports = buildSet {
                add(TransportKind.CLASSIC_SPP)
                if (device.bondState == BluetoothDevice.BOND_BONDED) {
                    add(TransportKind.BLE_GATT)
                }
            },
        )
    }

    private fun ByteArray.toLogHex(): String =
        joinToString(separator = "") { "%02X".format(it.toInt() and 0xFF) }

    private fun String.redactBluetoothAddresses(): String =
        replace(BLUETOOTH_ADDRESS_PATTERN, "<redacted-address>")

    private val BLUETOOTH_ADDRESS_PATTERN =
        Regex("(?i)(?:[0-9a-f]{2}:){5}[0-9a-f]{2}")

    private const val ENGINE_LOG_TAG = "HyperPodsConnect-Engine"
    private const val TERMINAL_SNAPSHOT_TIMEOUT_MILLIS = 2_000L
}
