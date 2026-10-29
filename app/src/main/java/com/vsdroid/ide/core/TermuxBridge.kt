package com.vsdroid.ide.core

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

data class ExecResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    /** Non-null when the command could not even be started. */
    val error: String? = null,
)

/**
 * Runs commands inside Termux (which provides a real Linux userland: python, git, dotnet, clang...).
 *
 * One-time setup inside Termux:
 *   1. echo "allow-external-apps=true" >> ~/.termux/termux.properties
 *   2. termux-setup-storage          (so Termux can see the same project folders)
 *   3. pkg install python           (add anything else you need)
 * Then fully close and reopen Termux once.
 */
object TermuxBridge {
    const val PACKAGE = "com.termux"
    const val PERMISSION = "com.termux.permission.RUN_COMMAND"
    const val HOME = "/data/data/com.termux/files/home"
    const val BASH = "/data/data/com.termux/files/usr/bin/bash"

    private const val ACTION_RUN = "com.termux.RUN_COMMAND"
    private const val SERVICE = "com.termux.app.RunCommandService"
    private const val EXTRA_ID = "vsdroid_exec_id"

    const val SETUP_HELP =
        "Termux is not ready yet. In Termux run:\n" +
            "  echo \"allow-external-apps=true\" >> ~/.termux/termux.properties\n" +
            "  termux-setup-storage\n" +
            "  pkg install python\n" +
            "then close Termux completely, reopen it once, and grant VSDroid the \"Run commands in Termux\" permission."

    private val pending = ConcurrentHashMap<Int, (ExecResult) -> Unit>()
    private val counter = AtomicInteger(1)

    fun isInstalled(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(PACKAGE, 0)
        true
    } catch (e: Exception) {
        false
    }

    fun hasPermission(ctx: Context): Boolean =
        ctx.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Runs [script] with bash in the background (no Termux window) and waits for the result. */
    suspend fun run(ctx: Context, script: String, workDir: File): ExecResult =
        suspendCancellableCoroutine { cont ->
            val app = ctx.applicationContext
            val id = counter.getAndIncrement()
            pending[id] = { r -> if (cont.isActive) cont.resume(r) }
            cont.invokeOnCancellation { pending.remove(id) }

            val resultIntent = Intent(app, TermuxResultReceiver::class.java).putExtra(EXTRA_ID, id)
            val pi = PendingIntent.getBroadcast(
                app, id, resultIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_MUTABLE,
            )
            val intent = Intent(ACTION_RUN).apply {
                setClassName(PACKAGE, SERVICE)
                putExtra("com.termux.RUN_COMMAND_PATH", BASH)
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", script))
                putExtra("com.termux.RUN_COMMAND_WORKDIR", workDir.path)
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pi)
            }
            try {
                app.startService(intent)
            } catch (e: Exception) {
                pending.remove(id)
                if (cont.isActive) cont.resume(ExecResult("", "", -1, e.message ?: e.javaClass.simpleName))
            }
        }

    internal fun onResult(intent: Intent) {
        val id = intent.getIntExtra(EXTRA_ID, -1)
        val callback = pending.remove(id) ?: return
        val b = intent.getBundleExtra("result")
        if (b == null) {
            callback(ExecResult("", "", -1, "Termux returned no result"))
            return
        }
        val err = b.getInt("err", -1)
        // Termux uses err = -1 (Activity.RESULT_CANCELED) when there was no internal error.
        val hasError = err != -1 && err != 0
        callback(
            ExecResult(
                stdout = b.getString("stdout") ?: "",
                stderr = b.getString("stderr") ?: "",
                exitCode = b.getInt("exitCode", 0),
                error = if (hasError) (b.getString("errmsg") ?: "Termux error $err") else null,
            ),
        )
    }
}

class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        TermuxBridge.onResult(intent)
    }
}
