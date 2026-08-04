package moe.chenxy.oppopods.runtime.bluetoothprocess

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
import kotlinx.coroutines.launch
import moe.chenxy.headphones.core.device.DeviceCandidate
import moe.chenxy.headphones.core.device.DeviceId
import moe.chenxy.headphones.core.device.DeviceIdentity
import moe.chenxy.headphones.core.device.TransportKind
import moe.chenxy.headphones.core.device.VendorId
import moe.chenxy.headphones.core.feature.FeatureId
import moe.chenxy.headphones.core.operation.FeatureCommand
import moe.chenxy.headphones.core.operation.OperationEvent
import moe.chenxy.headphones.core.operation.OperationResult
import moe.chenxy.headphones.core.session.DisconnectCause
import moe.chenxy.headphones.core.session.SessionState
import moe.chenxy.headphones.engine.DriverRegistry
import moe.chenxy.headphones.engine.HeadphoneSessionManager
import moe.chenxy.headphones.engine.HeadphoneSnapshot
import moe.chenxy.headphones.engine.SessionRuntimeHost
import moe.chenxy.headphones.protocol.oppo.compatibility.OppoCompatibilityOverrides
import moe.chenxy.headphones.protocol.oppo.session.OppoDriverProvider
import moe.chenxy.headphones.protocol.oppo.session.OppoSession
import moe.chenxy.headphones.protocol.oppo.session.OppoSessionEvent
import moe.chenxy.headphones.protocol.sony.frame.TandemCodec
import moe.chenxy.headphones.protocol.sony.message.SonyCommand
import moe.chenxy.headphones.protocol.sony.profile.SonyProfile
import moe.chenxy.headphones.protocol.sony.session.SonyDriverProvider
import moe.chenxy.headphones.protocol.sony.session.SonySession
import moe.chenxy.headphones.protocol.sony.session.SonySessionEvent
import moe.chenxy.headphones.transport.android.AndroidBluetoothTransportFactory
import moe.chenxy.oppopods.ipc.HeadphoneIpcContract
import moe.chenxy.oppopods.ipc.HeadphoneSnapshotPayload
import moe.chenxy.oppopods.ipc.IpcReplayGuard
import moe.chenxy.oppopods.ipc.IpcSenderPolicy
import moe.chenxy.oppopods.ipc.IpcCommandValidator
import moe.chenxy.oppopods.ipc.isSentFrom
import moe.chenxy.oppopods.ipc.sendIdentitySharedBroadcast
import moe.chenxy.oppopods.pods.OppoSystemIntegrationAdapter
import moe.chenxy.oppopods.BuildConfig

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
                    "OppoPods-Engine",
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
                if (value.profile?.vendorId == VendorId.OPPO) {
                    OppoSystemIntegrationAdapter.onEngineSnapshot(value)
                }
                publishSnapshot(value)
            }
        }
        scope.launch {
            manager.operations.collect { event ->
                if (snapshot.value?.profile?.vendorId == VendorId.OPPO) {
                    OppoSystemIntegrationAdapter.onEngineOperation(event)
                }
            }
        }
    }

    fun connect(
        context: Context,
        device: BluetoothDevice,
        overrides: OppoCompatibilityOverrides,
    ) {
        initialize(context)
        activeDevice = device
        registerDrivers(overrides)
        scope.launch {
            connect(
                candidate(device),
                AndroidBluetoothTransportFactory(
                    this@BluetoothProcessRuntimeHost.context,
                    device,
                    SonyAutoPlayGattDeviceResolver(this@BluetoothProcessRuntimeHost.context),
                ),
            )
        }
    }

    fun connectAutomatically(
        context: Context,
        device: BluetoothDevice,
        overrides: OppoCompatibilityOverrides,
    ) {
        initialize(context)
        registerDrivers(overrides)
        val candidate = candidate(device)
        val transportFactory = AndroidBluetoothTransportFactory(
            this.context,
            device,
            SonyAutoPlayGattDeviceResolver(this.context),
        )
        scope.launch {
            if (manager.autoConnect(candidate, transportFactory) != null) {
                activeDevice = device
            }
        }
    }

    fun canAutoConnect(
        device: BluetoothDevice,
        overrides: OppoCompatibilityOverrides,
    ): Boolean {
        registerDrivers(overrides)
        return registry.resolve(candidate(device))?.let(DriverRegistry::canAutoConnect) == true
    }

    private fun registerDrivers(overrides: OppoCompatibilityOverrides) {
        registry.register(OppoDriverProvider(overrides))
        registry.register(SonyDriverProvider())
    }

    override suspend fun connect(
        candidate: DeviceCandidate,
        transportFactory: moe.chenxy.headphones.core.transport.TransportFactory,
    ): Long = manager.connect(candidate, transportFactory)

    override suspend fun refresh(featureIds: Set<FeatureId>) = manager.refresh(featureIds)

    override suspend fun execute(command: FeatureCommand): OperationResult = manager.execute(command)

    fun executeAsync(command: FeatureCommand, requestId: String? = null) {
        scope.launch {
            manager.execute(command)
            publishCurrentSnapshot(requestId)
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
        val session = manager.activeSession() as? OppoSession ?: return false
        session.sendDebugFrame(bytes)
        return true
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
        when (val session = manager.activeSession()) {
            is OppoSession -> {
                vendorEventJob = scope.launch {
                    session.events.collect { event ->
                        if (snapshot.value?.generationId != generationId) return@collect
                        OppoSystemIntegrationAdapter.onOppoSessionEvent(event)
                    }
                }
            }
            is SonySession -> {
                vendorEventJob = scope.launch {
                    session.events.collect { event ->
                        if (snapshot.value?.generationId != generationId) return@collect
                        when (event) {
                            is SonySessionEvent.TxFrame ->
                                Log.d("OppoPods-Sony", "TX ${event.bytes.toLogHex()}")
                            // Capability-info can contain a device-unique id.
                            // Keep raw RX in memory for the active debug session,
                            // but never copy it into Android's persistent log.
                            is SonySessionEvent.RxChunk -> Unit
                            is SonySessionEvent.RxFrame -> {
                                val command = event.frame.payload.firstOrNull()?.toInt()?.and(0xFF)
                                if (command == SonyCommand.CONNECT_RET_CAPABILITY_INFO) {
                                    Log.d("OppoPods-Sony", "FRAME $event [redacted]")
                                } else {
                                    Log.d(
                                        "OppoPods-Sony",
                                        "RX ${TandemCodec.encode(event.frame).toLogHex()}",
                                    )
                                }
                            }
                            is SonySessionEvent.DecodeRejected ->
                                Log.w("OppoPods-Sony", "REJECT ${event.reason}")
                            is SonySessionEvent.AutoPlayTx ->
                                Log.d("OppoPods-Sony", "AUTO_PLAY TX ${event.bytes.toLogHex()}")
                            is SonySessionEvent.AutoPlayRx ->
                                Log.d("OppoPods-Sony", "AUTO_PLAY RX ${event.bytes.toLogHex()}")
                            is SonySessionEvent.AutoPlayUnavailable ->
                                Log.w("OppoPods-Sony", "AUTO_PLAY unavailable: ${event.detail}")
                        }
                    }
                }
            }
            else -> Unit
        }
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
            vendorId = inferredVendor(device.name, advertisedUuids),
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

    private fun inferredVendor(name: String?, advertisedUuids: Set<String>): VendorId? {
        if (advertisedUuids.any {
                it.equals(SonyProfile.SONY_SPP_V1_UUID, ignoreCase = true) ||
                    it.equals(SonyProfile.SONY_SPP_V2_UUID, ignoreCase = true)
            }
        ) return VendorId.SONY
        if (advertisedUuids.any {
                it.equals(OppoSession.OPPO_SPP_UUID, ignoreCase = true)
            }
        ) return VendorId.OPPO
        val displayName = name.orEmpty()
        return when {
            displayName.startsWith("WH-", ignoreCase = true) ||
                displayName.startsWith("WF-", ignoreCase = true) ||
                displayName.startsWith("WI-", ignoreCase = true) ||
                displayName.contains("LinkBuds", ignoreCase = true) -> VendorId.SONY
            displayName.contains("oppo", ignoreCase = true) ||
                displayName.contains("oneplus", ignoreCase = true) -> VendorId.OPPO
            else -> null
        }
    }

    private fun ByteArray.toLogHex(): String =
        joinToString(separator = "") { "%02X".format(it.toInt() and 0xFF) }

    private fun String.redactBluetoothAddresses(): String =
        replace(BLUETOOTH_ADDRESS_PATTERN, "<redacted-address>")

    private val BLUETOOTH_ADDRESS_PATTERN =
        Regex("(?i)(?:[0-9a-f]{2}:){5}[0-9a-f]{2}")
}
