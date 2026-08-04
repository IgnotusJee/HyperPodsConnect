package org.hyperpods.connect.hook

/** Independently gated HyperOS integration surfaces. */
enum class HyperOsHookGroup(val wireName: String) {
    MILINK_CORE("milink-core"),
    MILINK_SPATIAL("milink-spatial"),
    MILINK_UI("milink-ui"),
    OFFICIAL_ISLAND("official-island"),
    BLUETOOTH_BINDER("bluetooth-binder"),
    SETTINGS_HEADSET("settings-headset"),
}

enum class HyperOsHookTarget {
    ANDROID_BLUETOOTH,
    XIAOMI_BLUETOOTH,
    SETTINGS,
    MILINK_CORE,
    MILINK_UI,
}

/** Keeps hooks out of secondary and unrelated ROM processes. */
object HyperOsProcessRouter {
    fun targets(packageName: String, processName: String): Set<HyperOsHookTarget> = when {
        packageName == "com.android.bluetooth" && processName == packageName ->
            setOf(HyperOsHookTarget.ANDROID_BLUETOOTH)
        packageName == "com.xiaomi.bluetooth" && processName == packageName ->
            setOf(HyperOsHookTarget.XIAOMI_BLUETOOTH)
        packageName == "com.android.settings" && processName == packageName ->
            setOf(HyperOsHookTarget.SETTINGS)
        packageName == "com.milink.service" && processName == "com.milink.service:core" ->
            setOf(HyperOsHookTarget.MILINK_CORE)
        packageName == "com.milink.service" && processName == "com.milink.service:ui" ->
            setOf(HyperOsHookTarget.MILINK_UI)
        else -> emptySet()
    }
}

data class HyperOsMethodContract(
    val className: String,
    val methodName: String,
    val parameterTypes: List<Class<*>>,
) {
    val displayName: String
        get() = "$className#$methodName(${parameterTypes.joinToString { it.simpleName }})"
}

object HyperOsContractProbe {
    fun missing(
        required: List<HyperOsMethodContract>,
        available: (HyperOsMethodContract) -> Boolean,
    ): List<HyperOsMethodContract> = required.filterNot(available)
}

/**
 * Resolves one compatibility group atomically. A missing contract disables only that group and
 * emits one concise diagnostic instead of an exception for every process and every method.
 */
class HyperOsHookContractResolver(private val hook: HookContext) {
    private val reported = mutableSetOf<HyperOsHookGroup>()

    fun install(
        group: HyperOsHookGroup,
        required: List<HyperOsMethodContract>,
        block: () -> Unit,
    ): Boolean {
        val missing = HyperOsContractProbe.missing(required) { contract ->
            runCatching {
                hook.findMethod(
                    contract.className,
                    contract.methodName,
                    *contract.parameterTypes.toTypedArray(),
                )
            }.isSuccess
        }
        if (missing.isNotEmpty()) {
            report(group, "DISABLED", missing.joinToString { it.displayName })
            return false
        }
        return runCatching(block).fold(
            onSuccess = {
                report(group, "ACTIVE", null)
                true
            },
            onFailure = {
                report(group, "DISABLED", it.javaClass.simpleName + ":" + it.message)
                false
            },
        )
    }

    private fun report(group: HyperOsHookGroup, status: String, missing: String?) {
        if (!reported.add(group)) return
        Log.i(
            "HyperPodsConnect-Contract",
            buildString {
                append("HYPEROS_CONTRACT package=${hook.packageName} process=${hook.processName} ")
                append("group=${group.wireName} status=$status")
                if (!missing.isNullOrBlank()) append(" missing=$missing")
            },
        )
    }
}
