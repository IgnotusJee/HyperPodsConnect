package moe.chenxy.oppopods.runtime.bluetoothprocess

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import moe.chenxy.headphones.engine.DriverRegistry
import moe.chenxy.headphones.engine.HeadphoneSessionManager
import moe.chenxy.headphones.engine.HeadphoneSnapshot
import moe.chenxy.headphones.engine.SessionRuntimeHost
import moe.chenxy.headphones.protocol.oppo.compatibility.OppoCompatibilityOverrides
import moe.chenxy.headphones.protocol.oppo.session.OppoDriverProvider
import moe.chenxy.headphones.protocol.oppo.session.OppoSession
import moe.chenxy.headphones.protocol.oppo.session.OppoSessionEvent
import moe.chenxy.headphones.transport.android.AndroidSppTransportFactory
import moe.chenxy.oppopods.ipc.HeadphoneIpcContract
import moe.chenxy.oppopods.ipc.HeadphoneSnapshotPayload
import moe.chenxy.oppopods.pods.RfcommController

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
                attachVendorEvents(value.generationId)
                RfcommController.onEngineSnapshot(value)
                publishSnapshot(value)
            }
        }
        scope.launch {
            manager.operations.collect(RfcommController::onEngineOperation)
        }
    }

    fun connect(
        context: Context,
        device: BluetoothDevice,
        overrides: OppoCompatibilityOverrides,
    ) {
        initialize(context)
        activeDevice = device
        registry.register(OppoDriverProvider(overrides))
        scope.launch {
            connect(candidate(device), AndroidSppTransportFactory(device))
        }
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
        val session = manager.activeSession() as? OppoSession ?: return false
        session.sendDebugFrame(bytes)
        return true
    }

    fun publishCurrentSnapshot(requestId: String? = null) {
        snapshot.value?.let { publishSnapshot(it, requestId) }
    }

    private fun publishSnapshot(value: HeadphoneSnapshot, requestId: String? = null) {
        if (!::context.isInitialized) return
        val payload = HeadphoneSnapshotPayload.from(value)
        HeadphoneIpcContract.eventTargets.forEach { target ->
            context.sendBroadcast(
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
        val session = manager.activeSession() as? OppoSession ?: return
        vendorEventJob = scope.launch {
            session.events.collect { event ->
                if (snapshot.value?.generationId != generationId) return@collect
                RfcommController.onOppoSessionEvent(event)
            }
        }
    }

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val envelope = intent?.let(HeadphoneIpcContract::decodeCommand) ?: return
            val current = snapshot.value
            if (
                envelope.deviceId != null &&
                current != null &&
                envelope.deviceId != current.deviceId.value
            ) return
            if (
                envelope.vendorId != null &&
                current?.profile != null &&
                envelope.vendorId != current.profile?.vendorId?.value
            ) return
            if (envelope.payload.type == HeadphoneIpcContract.TYPE_REQUEST_SNAPSHOT) {
                publishCurrentSnapshot(envelope.requestId)
                return
            }
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
        val identity = DeviceIdentity(
            id = DeviceId.fromAddress(device.address),
            vendorId = VendorId.OPPO,
            primaryAddress = device.address,
        )
        return DeviceCandidate(
            identity = identity,
            displayName = device.name,
            bonded = device.bondState == BluetoothDevice.BOND_BONDED,
            advertisedUuids = device.uuids?.map { it.uuid.toString() }?.toSet().orEmpty(),
            availableTransports = setOf(TransportKind.CLASSIC_SPP),
        )
    }
}
