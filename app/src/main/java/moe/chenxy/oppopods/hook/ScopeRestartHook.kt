package moe.chenxy.oppopods.hook

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.Process
import moe.chenxy.oppopods.ipc.HeadphoneIpcContract
import moe.chenxy.oppopods.ipc.IpcSenderPolicy
import moe.chenxy.oppopods.ipc.isSentFrom

/** Root-free restart endpoint installed into each selected LSPosed scope process. */
class ScopeRestartHook : HookContext() {
    private var receiverRegistered = false

    override fun onHook() {
        runCatching {
            hookAfter(findMethod(Application::class.java.name, "attach", Context::class.java)) {
                registerReceiver(args[0] as? Context)
            }
        }.onFailure {
            Log.e(TAG, "Application.attach hook failed package=$packageName", it)
        }
    }

    private fun registerReceiver(context: Context?) {
        if (context == null || receiverRegistered) return
        val appContext = context.applicationContext ?: context
        appContext.registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action != HeadphoneIpcContract.ACTION_RESTART_SCOPE) return
                    if (!isSentFrom(IpcSenderPolicy.moduleOnly)) return
                    Log.i(TAG, "restarting hooked process package=$packageName pid=${Process.myPid()}")
                    Handler(Looper.getMainLooper()).postDelayed(
                        { Process.killProcess(Process.myPid()) },
                        100L,
                    )
                }
            },
            IntentFilter(HeadphoneIpcContract.ACTION_RESTART_SCOPE),
            Context.RECEIVER_EXPORTED,
        )
        receiverRegistered = true
        Log.i(TAG, "restart receiver registered package=$packageName pid=${Process.myPid()}")
    }

    private companion object {
        const val TAG = "OppoPods-ScopeRestart"
    }
}
