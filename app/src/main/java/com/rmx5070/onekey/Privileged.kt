package com.rmx5070.onekey

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.io.InputStream
import java.io.OutputStream

/** A process spawned by the Shizuku server (runs as uid 2000 / shell). */
class PrivProcess(
    private val inS: InputStream,
    private val outS: OutputStream,
    private val errS: InputStream,
    private val waiter: () -> Int,
) {
    fun getInputStream(): InputStream = inS
    fun getOutputStream(): OutputStream = outS
    fun getErrorStream(): InputStream = errS
    fun waitFor(): Int = waiter()
}

/** Thin wrapper around the Shizuku privileged backend. */
object PrivService {

    fun binderAlive(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun granted(): Boolean = runCatching {
        binderAlive() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun request(code: Int) {
        runCatching { Shizuku.requestPermission(code) }
    }

    fun newProcess(cmd: Array<String>, env: Array<String>?, dir: String?): PrivProcess {
        val binder = Shizuku.getBinder() ?: error("Shizuku binder not available")
        val svc = IShizukuService.Stub.asInterface(binder) ?: error("Shizuku service not available")
        val p = svc.newProcess(cmd, env, dir)
        return PrivProcess(
            ParcelFileDescriptor.AutoCloseInputStream(p.getInputStream()),
            ParcelFileDescriptor.AutoCloseOutputStream(p.getOutputStream()),
            ParcelFileDescriptor.AutoCloseInputStream(p.getErrorStream()),
        ) { p.waitFor() }
    }

    fun openShizuku(context: Context) {
        val intent = context.packageManager
            .getLaunchIntentForPackage("moe.shizuku.privileged.api")
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent != null) runCatching { context.startActivity(intent) }
    }

    fun shizukuVersion(): Int = runCatching { Shizuku.getVersion() }.getOrDefault(0)
}
