package com.rmx5070.onekey

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.rmx5070.onekey.ui.Rmx5070Theme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val logs = mutableStateListOf<String>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    private var busy by mutableStateOf(false)
    private var binderReady by mutableStateOf(false)
    private var authorized by mutableStateOf(false)

    private var stage by mutableStateOf(JbStage.IDLE)
    private var stepText by mutableStateOf("")

    private var kernelVersion by mutableStateOf("Unknown")
    private var deviceModel by mutableStateOf("Unknown")
    private var deviceState by mutableStateOf(DeviceState())
    private var stateLoading by mutableStateOf(true)
    private var isTarget by mutableStateOf(true)

    private var tab by mutableStateOf(Tab.HOME)

    private var historyEntries by mutableStateOf(listOf<HistoryEntry>())
    private var historyDetail by mutableStateOf<HistoryEntry?>(null)
    private var historyDetailText by mutableStateOf("")

    private var showPermissionDialog by mutableStateOf(false)
    private var showBackendDialog by mutableStateOf(false)
    private var showRebootDialog by mutableStateOf(false)
    private var showDirtyDialog by mutableStateOf(false)
    private var outcome by mutableStateOf<JbOutcome?>(null)
    private var dirtyThisBoot by mutableStateOf(false)

    private val permissionListener = rikka.shizuku.Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code == REQ_SHIZUKU) onPermissionResult(result == android.content.pm.PackageManager.PERMISSION_GRANTED)
    }
    private val binderListener = rikka.shizuku.Shizuku.OnBinderReceivedListener {
        refreshBackend()
        refreshStateAsync(showLoading = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        rikka.shizuku.Shizuku.addRequestPermissionResultListener(permissionListener)
        rikka.shizuku.Shizuku.addBinderReceivedListenerSticky(binderListener)

        kernelVersion = DeviceInfo.kernelRelease()
        deviceModel = DeviceInfo.model()
        isTarget = DeviceInfo.isTargetDevice()
        val bootId = DeviceInfo.bootId()
        dirtyThisBoot = bootId.isNotEmpty() &&
            getSharedPreferences("rmx5070", MODE_PRIVATE).getString("dirty_boot_id", "") == bootId

        refreshBackend()
        refreshStateAsync(showLoading = true)

        setContent {
            Rmx5070Theme {
                AppRoot()
            }
        }
    }

    // ------------------------------------------------------------------ state

    private fun refreshBackend() {
        binderReady = PrivService.binderAlive()
        authorized = PrivService.granted()
        if (binderReady && !authorized) showPermissionDialog = true
    }

    private fun onPermissionResult(ok: Boolean) {
        authorized = ok
        addLog(if (ok) "Shizuku permission granted" else "Shizuku permission denied")
        showPermissionDialog = false
        if (ok) refreshStateAsync()
    }

    private fun refreshStateAsync(showLoading: Boolean = false) {
        if (!PrivService.granted()) {
            stateLoading = false
            return
        }
        if (showLoading) stateLoading = true
        lifecycleScope.launch(Dispatchers.IO) {
            val engine = JailbreakEngine(applicationContext, {}, { _, _ -> })
            // Publish the fast fields first so the home screen never shows a
            // stale "not jailbroken" while the stale-plant scan runs.
            val fast = engine.readFastState()
            withContext(Dispatchers.Main) {
                deviceState = fast
                stateLoading = false
            }
            // KSU already implies a dirty/exploited boot, so skip the scan.
            if (!fast.ksuLoaded) {
                val stale = engine.readStaleFlag()
                withContext(Dispatchers.Main) {
                    deviceState = deviceState.copy(stalePlant = stale)
                }
            }
        }
    }

    private fun addLog(line: String) {
        val stamped = "${timeFmt.format(Date())}  $line"
        HistoryStore.append(stamped)
        runOnUiThread {
            logs.add(stamped)
            if (logs.size > 5000) logs.removeRange(0, logs.size - 5000)
        }
    }

    private fun toast(msg: String) = runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

    // ------------------------------------------------------------------ flow

    private fun startJailbreak() {
        if (busy) return
        if (!binderReady) { showBackendDialog = true; return }
        if (!authorized) { PrivService.request(REQ_SHIZUKU); return }

        busy = true
        outcome = null
        stage = JbStage.IDLE
        stepText = ""
        addLog("=== Starting one-click jailbreak ===")
        val startedAt = System.currentTimeMillis()
        HistoryStore.start(applicationContext, deviceModel, kernelVersion)

        lifecycleScope.launch(Dispatchers.IO) {
            val engine = JailbreakEngine(
                appContext = applicationContext,
                onLog = { addLog(it) },
                onStage = { s, t ->
                    runOnUiThread { stage = s; stepText = t }
                },
                autoInstallManager = true,
            )
            val res = try {
                engine.run()
            } catch (t: Throwable) {
                JbOutcome(false, "Unexpected error: ${t.message}")
            }
            HistoryStore.finish(res.success, if (res.success) 0 else 1, System.currentTimeMillis() - startedAt)
            withContext(Dispatchers.Main) {
                outcome = res
                busy = false
                stage = if (res.success) JbStage.DONE else JbStage.FAILED
                stepText = res.message.lineSequence().firstOrNull().orEmpty()
                if (res.kernelUnstable) showRebootDialog = true
                if (res.kernelDirty) {
                    val bid = DeviceInfo.bootId()
                    if (bid.isNotEmpty()) {
                        getSharedPreferences("rmx5070", MODE_PRIVATE).edit()
                            .putString("dirty_boot_id", bid).apply()
                    }
                    dirtyThisBoot = true
                }
                addLog(if (res.success) "=== Jailbreak succeeded ===" else "=== Jailbreak failed: ${res.message.lineSequence().first()} ===")
            }
            refreshStateAsync()
        }
    }

    private fun refreshHistory() {
        lifecycleScope.launch(Dispatchers.IO) {
            val entries = HistoryStore.list(applicationContext)
            withContext(Dispatchers.Main) { historyEntries = entries }
        }
    }

    // -------------------------------------------------------------------- UI

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun AppRoot() {
        // One scroll behavior per expandable page, kept at the root so the bar
        // and its list share the same collapse state.
        val homeScroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
        val historyScroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

        // Pages are rebuilt when the tab changes, so put the bar back to its
        // expanded pose instead of leaving it collapsed over a fresh list.
        LaunchedEffect(tab) {
            homeScroll.state.heightOffset = 0f
            homeScroll.state.contentOffset = 0f
            historyScroll.state.heightOffset = 0f
            historyScroll.state.contentOffset = 0f
        }

        Scaffold(
            topBar = {
                when (tab) {
                    Tab.HOME -> LargeTopAppBar(
                        title = {
                            val collapse = homeScroll.state.collapsedFraction
                            val faded = (1f - collapse).coerceIn(0f, 1f)
                            val muted = MaterialTheme.colorScheme.onSurfaceVariant
                            val link = MaterialTheme.colorScheme.primary
                            Column(
                                // The subtitle and byline still occupy layout
                                // space when faded out; nudge the block down so
                                // the title sits centred in the collapsed bar
                                // like a regular TopAppBar title.
                                Modifier.offset(y = 16.dp * collapse),
                            ) {
                                Text("Liberate-P3", fontWeight = FontWeight.SemiBold)
                                Text(
                                    "One-click jailbreak for Realme P3",
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp,
                                    color = muted,
                                    modifier = Modifier.alpha(faded),
                                )
                                Text(
                                    buildAnnotatedString {
                                        withStyle(SpanStyle(color = muted)) {
                                            append("by archixman. ")
                                        }
                                        withLink(
                                            LinkAnnotation.Url(
                                                url = SOURCE_URL,
                                                styles = TextLinkStyles(
                                                    style = SpanStyle(
                                                        color = link,
                                                        textDecoration = TextDecoration.Underline,
                                                    ),
                                                ),
                                            )
                                        ) {
                                            append("Source code")
                                        }
                                    },
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp,
                                    modifier = Modifier.alpha(faded),
                                )
                            }
                        },
                        actions = {
                            IconButton(onClick = { refreshBackend(); refreshStateAsync() }) {
                                Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                            }
                        },
                        scrollBehavior = homeScroll,
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                    )
                    Tab.LOGS -> TopAppBar(
                        title = { Text("Logs", fontWeight = FontWeight.SemiBold) },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                    )
                    Tab.HISTORY -> LargeTopAppBar(
                        title = { Text("History", fontWeight = FontWeight.SemiBold) },
                        scrollBehavior = historyScroll,
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                    )
                }
            },
            bottomBar = {
                NavigationBar {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { Icon(t.icon, contentDescription = t.label) },
                            label = { Text(t.label) },
                        )
                    }
                }
            },
        ) { inner ->
            when (tab) {
                Tab.HOME -> HomeScreen(Modifier.padding(inner), homeScroll)
                Tab.LOGS -> LogsScreen(Modifier.padding(inner))
                Tab.HISTORY -> HistoryScreen(Modifier.padding(inner), historyScroll)
            }
        }

        PermissionDialog()
        BackendDialog()
        RebootDialog()
        DirtyDialog()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun HomeScreen(modifier: Modifier, scrollBehavior: TopAppBarScrollBehavior) {
        LazyColumn(
            modifier = modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { StatusCard() }
            item { ActionArea() }

            outcome?.let { res ->
                if (!res.success) item { ErrorCard(res.message) }
                if (res.success && res.managerMissing) item { ManagerInstallCard() }
            }

            item { DeviceCard() }
            item { SafetyCard() }
        }
    }

    private fun isDirtyNow(): Boolean =
        dirtyThisBoot || deviceState.ksuLoaded || deviceState.stalePlant

    @Composable
    private fun DirtyDialog() {
        if (!showDirtyDialog) return
        AlertDialog(
            onDismissRequest = { showDirtyDialog = false },
            icon = {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = { Text("Kernel timer state is dirty") },
            text = {
                Text(
                    "This boot's exploit touched a POSIX-timer node. KernelSU still works. " +
                        "KernelSU is not persistent, so reboot when you are done."
                )
            },
            confirmButton = {
                TextButton(onClick = { showDirtyDialog = false }) { Text("Understood") }
            },
        )
    }

    @Composable
    private fun StatusCard() {
        val rooted = deviceState.ksuLoaded && deviceState.suAvailable
        val container: Color
        val content: Color
        when {
            stateLoading -> {
                container = MaterialTheme.colorScheme.surfaceVariant
                content = MaterialTheme.colorScheme.onSurfaceVariant
            }
            rooted -> {
                container = MaterialTheme.colorScheme.primaryContainer
                content = MaterialTheme.colorScheme.onPrimaryContainer
            }
            !binderReady -> {
                container = MaterialTheme.colorScheme.surfaceVariant
                content = MaterialTheme.colorScheme.onSurfaceVariant
            }
            else -> {
                container = MaterialTheme.colorScheme.errorContainer
                content = MaterialTheme.colorScheme.onErrorContainer
            }
        }
        val (title, subtitle) = when {
            stateLoading -> "Checking status..." to "Reading KernelSU / SELinux state"
            rooted -> "Jailbroken" to "KernelSU is loaded"
            !binderReady -> "Shizuku is not running" to "Start the Shizuku service first"
            !authorized -> "Shizuku not authorized" to "Tap the button below to grant access"
            deviceState.ksuLoaded -> "Module loaded" to "Verifying su, please wait"
            else -> "Not jailbroken" to "Ready for one-click jailbreak"
        }
        val dirty = isDirtyNow() && !stateLoading
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (dirty) Modifier.clickable { showDirtyDialog = true } else Modifier),
            colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    when {
                        rooted -> Icons.Filled.CheckCircle
                        binderReady -> Icons.Filled.Lock
                        else -> Icons.Filled.Warning
                    },
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(2.dp))
                    Text(subtitle, fontSize = 14.sp)
                }
                if (dirty) {
                    Icon(
                        Icons.Filled.Info,
                        contentDescription = "Kernel state details",
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }

    @Composable
    private fun ActionArea() {
        val label = when {
            busy -> "Running..."
            !binderReady -> "Start Shizuku"
            !authorized -> "Grant Shizuku access"
            stateLoading -> "Checking status..."
            deviceState.ksuLoaded -> "Verify / run again"
            else -> "One-click jailbreak"
        }
        Column(modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    when {
                        busy -> Unit
                        !binderReady -> showBackendDialog = true
                        !authorized -> PrivService.request(REQ_SHIZUKU)
                        stateLoading -> Unit
                        else -> startJailbreak()
                    }
                },
                enabled = !busy && !(stateLoading && authorized),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(10.dp))
                } else {
                    Icon(Icons.Filled.BugReport, contentDescription = null)
                    Spacer(Modifier.width(10.dp))
                }
                Text(label)
            }

            if (busy || stage != JbStage.IDLE) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Stage: ${stage.label}",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (stepText.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(stepText, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    @Composable
    private fun ManagerInstallCard() {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("KernelSU manager is not installed", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Root is already available. Install the manager to handle grants, modules and superuser.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = { installManagerFromAsset() }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Lock, contentDescription = null)
                    Spacer(Modifier.width(10.dp))
                    Text("Install KernelSU manager")
                }
            }
        }
    }

    /** Extracts the bundled manager to cache and hands it to the system installer. */
    private fun installManagerFromAsset() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val apk = java.io.File(cacheDir, "KernelSU.apk")
                assets.open("rmx5070/KernelSU.apk").use { ins ->
                    apk.outputStream().use { out -> ins.copyTo(out) }
                }
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    this@MainActivity,
                    "$packageName.fileprovider",
                    apk,
                )
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                withContext(Dispatchers.Main) {
                    try {
                        startActivity(intent)
                    } catch (t: Throwable) {
                        addLog("Could not launch the system installer: ${t.message}")
                        // Unknown-sources permission may be missing; send the user there.
                        runCatching {
                            startActivity(
                                android.content.Intent(
                                    android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    android.net.Uri.parse("package:$packageName"),
                                )
                            )
                        }
                        toast("Allow this app to install unknown apps, then retry")
                    }
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) { addLog("Manager install failed: ${t.message}") }
            }
        }
    }

    @Composable
    private fun ErrorCard(message: String) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
        ) {
            Row(modifier = Modifier.padding(16.dp)) {
                Icon(Icons.Filled.ErrorOutline, contentDescription = null, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Text(message, fontSize = 14.sp)
            }
        }
    }

    @Composable
    private fun DeviceCard() {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                InfoRow(Icons.Filled.Smartphone, "Device model", deviceModel)
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                InfoRow(Icons.Filled.Memory, "Kernel version", kernelVersion)
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                InfoRow(Icons.Filled.Info, "System", DeviceInfo.androidRelease())
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                InfoRow(Icons.Filled.Security, "SELinux", deviceState.selinux)
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                InfoRow(
                    Icons.Filled.CheckCircle,
                    "su",
                    if (deviceState.suAvailable) "Available (uid=0)" else "Not available",
                )
                if (!isTarget) {
                    HorizontalDivider(Modifier.padding(vertical = 10.dp))
                    InfoRow(Icons.Filled.Warning, "Target check", "Not an RMX5070; running is disabled")
                }
            }
        }
    }

    @Composable
    private fun InfoRow(icon: ImageVector, title: String, value: String) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(14.dp))
            Column {
                Text(title, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
        }
    }

    @Composable
    private fun SafetyCard() {
        var expanded by remember { mutableStateOf(false) }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Safety notes",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                    )
                }
                AnimatedVisibility(visible = expanded) {
                    Column {
                        Spacer(Modifier.height(8.dp))
                        SafetyLine("Changes are in memory only; KernelSU is not persistent across reboots (this is expected).")
                        SafetyLine("Run once per boot. If a leftover t98260 process is detected, cold-reboot first.")
                        SafetyLine("If a fatal kernel error or a reboot suggestion appears, cold-reboot immediately.")
                    }
                }
            }
        }
    }

    @Composable
    private fun SafetyLine(text: String) {
        Row(modifier = Modifier.padding(vertical = 3.dp)) {
            Text("•  ", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    @Composable
    private fun LogsScreen(modifier: Modifier) {
        val state = logs
        if (state.isEmpty()) {
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No logs yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return
        }
        val listState = rememberLazyListState()
        LaunchedEffect(state.size) { listState.animateScrollToItem(state.lastIndex) }
        LazyColumn(
            state = listState,
            modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(state) { line ->
                Text(line, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun HistoryScreen(modifier: Modifier, scrollBehavior: TopAppBarScrollBehavior) {
        val detail = historyDetail
        if (detail != null) {
            HistoryDetail(detail, modifier, scrollBehavior)
            return
        }
        // Opening or closing an entry rebuilds the list at the top, so expand
        // the bar again to match.
        LaunchedEffect(detail) {
            scrollBehavior.state.heightOffset = 0f
            scrollBehavior.state.contentOffset = 0f
        }
        LaunchedEffect(Unit) { refreshHistory() }
        val entries = historyEntries
        if (entries.isEmpty()) {
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No history yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return
        }
        LazyColumn(
            modifier = modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(entries) { e ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable {
                        lifecycleScope.launch(Dispatchers.IO) {
                            val text = HistoryStore.read(applicationContext, e.fileName)
                            withContext(Dispatchers.Main) {
                                historyDetailText = text
                                historyDetail = e
                            }
                        }
                    },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.History, contentDescription = null, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.startTime, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                            Text(
                                "${e.device} · ${e.kernel.take(24)}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            when (e.result) {
                                "SUCCESS" -> "Success"
                                "FAIL" -> "Failed"
                                else -> "Running"
                            },
                            fontSize = 13.sp,
                            color = if (e.result == "SUCCESS")
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun HistoryDetail(
        entry: HistoryEntry,
        modifier: Modifier,
        scrollBehavior: TopAppBarScrollBehavior,
    ) {
        Column(modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { historyDetail = null }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Text(entry.startTime, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                IconButton(onClick = {
                    lifecycleScope.launch(Dispatchers.IO) {
                        HistoryStore.delete(applicationContext, entry.fileName)
                        withContext(Dispatchers.Main) {
                            historyDetail = null
                            refreshHistory()
                        }
                    }
                }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete")
                }
            }
            val lines = remember(historyDetailText) { historyDetailText.lines() }
            LazyColumn(
                modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(lines) { line ->
                    Text(line, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }

    @Composable
    private fun PermissionDialog() {
        if (!showPermissionDialog || authorized) return
        AlertDialog(
            onDismissRequest = { showPermissionDialog = false },
            icon = { Icon(Icons.Filled.Security, contentDescription = null) },
            title = { Text("Shizuku permission required") },
            text = {
                Text("This app runs the exploit toolchain as the shell user through Shizuku. Grant Shizuku access to continue.")
            },
            confirmButton = {
                TextButton(onClick = {
                    PrivService.request(REQ_SHIZUKU)
                    showPermissionDialog = false
                }) { Text("Grant") }
            },
            dismissButton = {
                TextButton(onClick = {
                    PrivService.openShizuku(this@MainActivity)
                    showPermissionDialog = false
                }) { Text("Open Shizuku") }
            },
        )
    }

    @Composable
    private fun BackendDialog() {
        if (!showBackendDialog) return
        AlertDialog(
            onDismissRequest = { showBackendDialog = false },
            icon = { Icon(Icons.Filled.Warning, contentDescription = null) },
            title = { Text("Shizuku is not running") },
            text = { Text("Start the Shizuku service first (via wireless debugging or an existing root), then return to this app.") },
            confirmButton = {
                TextButton(onClick = {
                    PrivService.openShizuku(this@MainActivity)
                    showBackendDialog = false
                }) { Text("Open Shizuku") }
            },
            dismissButton = {
                TextButton(onClick = { showBackendDialog = false }) { Text("Cancel") }
            },
        )
    }

    @Composable
    private fun RebootDialog() {
        if (!showRebootDialog) return
        AlertDialog(
            onDismissRequest = { showRebootDialog = false },
            icon = { Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Reboot recommended") },
            text = {
                Text(
                    "The kernel log shows a corrupted timer rbtree warning. Continuing may trigger a kernel panic.\n" +
                        "Reboot the phone now; KernelSU will not persist, which is expected."
                )
            },
            confirmButton = {
                TextButton(onClick = { showRebootDialog = false }) { Text("Understood") }
            },
        )
    }

    override fun onResume() {
        super.onResume()
        // Keep the home screen truthful when returning to the app, not just
        // on the first onCreate.
        refreshBackend()
        refreshStateAsync()
    }

    override fun onDestroy() {
        rikka.shizuku.Shizuku.removeRequestPermissionResultListener(permissionListener)
        rikka.shizuku.Shizuku.removeBinderReceivedListener(binderListener)
        super.onDestroy()
    }

    private enum class Tab(val label: String, val icon: ImageVector) {
        HOME("Home", Icons.Filled.Home),
        LOGS("Logs", Icons.Filled.Terminal),
        HISTORY("History", Icons.Filled.History),
    }

    companion object {
        private const val REQ_SHIZUKU = 1001
        private const val SOURCE_URL = "https://github.com/archixman/Liberate-P3"
    }
}