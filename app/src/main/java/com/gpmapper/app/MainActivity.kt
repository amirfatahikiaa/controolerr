package com.gpmapper.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gpmapper.app.service.MappingService
import com.gpmapper.app.service.OverlayService
import com.gpmapper.app.model.ProfileManager
import com.gpmapper.app.input.DiagnosticTestRunner
import com.gpmapper.app.input.InjectionBackend
import com.gpmapper.app.input.ShizukuDaemonBackend
import com.gpmapper.app.poc.PoCActivity
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF1A73E8),
                    surface = Color(0xFF1E1E2E),
                    background = Color(0xFF121218),
                    onSurface = Color(0xFFE0E0E0),
                    onBackground = Color(0xFFE0E0E0)
                )
            ) {
                GPMapperUI()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GPMapperUI() {
    val context = LocalContext.current
    val app = context.applicationContext as GPMapperApp
    val scope = rememberCoroutineScope()

    var isMappingActive by remember { mutableStateOf(false) }
    var isOverlayActive by remember { mutableStateOf(false) }
    var currentProfile by remember { mutableStateOf("FC Mobile Default") }
    var shizukuRunning by remember { mutableStateOf(false) }
    var shizukuAuthorized by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var diagnosticReport by remember { mutableStateOf<DiagnosticTestRunner.FullDiagnosticReport?>(null) }
    var isRunningDiagnostics by remember { mutableStateOf(false) }
    var latencyStats by remember { mutableStateOf<MappingService.LatencyTracker.Stats?>(null) }
    var backendDiagnostics by remember { mutableStateOf<InjectionBackend.BackendDiagnostics?>(null) }

    LaunchedEffect(Unit) {
        shizukuRunning = app.checkShizukuRunning()
        shizukuAuthorized = GPMapperApp.shizukuAuthorized
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("GP-Mapper", fontWeight = FontWeight.Bold, fontSize = 22.sp)
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1A73E8)
                ),
                actions = {
                    IconButton(onClick = { showDiagnostics = !showDiagnostics }) {
                        Icon(
                            if (showDiagnostics) Icons.Default.VisibilityOff else Icons.Default.BugReport,
                            contentDescription = "Diagnostics"
                        )
                    }
                    IconButton(onClick = { }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        containerColor = Color(0xFF121218)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E2E)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("System Status", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White)
                    Spacer(modifier = Modifier.height(8.dp))
                    StatusRow("Shizuku", shizukuRunning && shizukuAuthorized)
                    StatusRow("Controller", isMappingActive)
                    StatusRow("Overlay", isOverlayActive)

                    if (!shizukuRunning) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { app.requestShizukuPermission(1001) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Request Shizuku Permission", fontSize = 12.sp)
                        }
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E2E)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("Mapping Service", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White)
                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = {
                            if (isMappingActive) {
                                MappingService.stop(context)
                            } else {
                                MappingService.start(context)
                            }
                            isMappingActive = !isMappingActive
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isMappingActive) Color(0xFFFF5252) else Color(0xFF1A73E8)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            if (isMappingActive) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (isMappingActive) "Stop Mapping" else "Start Mapping")
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = {
                            if (isOverlayActive) {
                                OverlayService.stop(context)
                            } else {
                                OverlayService.start(context)
                            }
                            isOverlayActive = !isOverlayActive
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isOverlayActive) Color(0xFFFF9800) else Color(0xFF2E7D32)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            if (isOverlayActive) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (isOverlayActive) "Hide Overlay" else "Show Overlay")
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E2E)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("Profile", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Active: $currentProfile", fontSize = 14.sp, color = Color(0xFF90CAF9))
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(
                            onClick = { currentProfile = "FC Mobile Default" },
                            label = { Text("FC Mobile") }
                        )
                        AssistChip(
                            onClick = { currentProfile = "MOBA Default" },
                            label = { Text("MOBA") }
                        )
                    }
                }
            }

            if (showDiagnostics) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2A1A1A)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text("Diagnostics", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color(0xFFFF5252))
                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = {
                                context.startActivity(android.content.Intent(context, PoCActivity::class.java))
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A73E8)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.BugReport, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Run IInputManager PoC")
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Button(
                            onClick = {
                                scope.launch {
                                    isRunningDiagnostics = true
                                    val backend = ShizukuDaemonBackend()
                                    if (backend.checkAvailability()) {
                                        backend.initialize(context)
                                        val runner = DiagnosticTestRunner(backend)
                                        diagnosticReport = runner.runFullDiagnostic()
                                    } else {
                                        diagnosticReport = DiagnosticTestRunner.FullDiagnosticReport(
                                            results = emptyList(),
                                            totalTests = 0,
                                            passedTests = 0,
                                            failedTests = 0,
                                            backendDiagnostics = InjectionBackend.BackendDiagnostics(
                                                "None", false, 0, 0, "No backend available", 0
                                            ),
                                            timestampMs = System.currentTimeMillis()
                                        )
                                    }
                                    isRunningDiagnostics = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isRunningDiagnostics,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            if (isRunningDiagnostics) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Running...")
                            } else {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Run Full Diagnostic")
                            }
                        }

                        diagnosticReport?.let { report ->
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                "Results: ${report.passedTests}/${report.totalTests} passed",
                                fontSize = 14.sp,
                                color = if (report.failedTests == 0) Color(0xFF4CAF50) else Color(0xFFFF5252)
                            )
                            Text("Backend: ${report.backendDiagnostics.backendName}", fontSize = 12.sp, color = Color(0xFF90A4AE))
                            Text("Injected: ${report.backendDiagnostics.totalInjected} | Failed: ${report.backendDiagnostics.failedInjected}", fontSize = 12.sp, color = Color(0xFF90A4AE))
                            report.backendDiagnostics.lastError?.let {
                                Text("Last error: $it", fontSize = 12.sp, color = Color(0xFFFF9800))
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            for (result in report.results) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(result.testName, fontSize = 11.sp, color = Color(0xFFBBDEFB))
                                    Text(
                                        if (result.passed) "PASS" else "FAIL",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (result.passed) Color(0xFF4CAF50) else Color(0xFFFF5252)
                                    )
                                }
                                if (!result.passed) {
                                    Text(
                                        "  ${result.errorMessage ?: "unknown"} (${result.durationMs}ms)",
                                        fontSize = 10.sp,
                                        color = Color(0xFF757575)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Latency Stats", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.White)
                        Spacer(modifier = Modifier.height(4.dp))
                        latencyStats?.let { stats ->
                            Text("Samples: ${stats.sampleCount}", fontSize = 12.sp, color = Color(0xFF90A4AE))
                            Text("Mapping: avg=${"%.1f".format(stats.avgMappingUs)}us p50=${"%.1f".format(stats.p50MappingUs)}us p95=${"%.1f".format(stats.p95MappingUs)}us", fontSize = 11.sp, color = Color(0xFFBBDEFB))
                            Text("E2E: avg=${"%.1f".format(stats.avgE2EUs)}us p50=${"%.1f".format(stats.p50E2EUs)}us p95=${"%.1f".format(stats.p95E2EUs)}us", fontSize = 11.sp, color = Color(0xFFBBDEFB))
                        } ?: Text("No latency data yet", fontSize = 12.sp, color = Color(0xFF757575))

                        Spacer(modifier = Modifier.height(8.dp))
                        backendDiagnostics?.let { diag ->
                            Text("Backend: ${diag.backendName}", fontSize = 12.sp, color = Color(0xFF90A4AE))
                            Text("Uptime: ${diag.uptimeMs / 1000}s", fontSize = 12.sp, color = Color(0xFF90A4AE))
                        }
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E2E)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("Button Mapping Quick View", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White)
                    Spacer(modifier = Modifier.height(8.dp))
                    MappingRow("Cross (X)", "Tap at button location")
                    MappingRow("Circle", "Tap / Directional Flick")
                    MappingRow("Square", "Tap at button location")
                    MappingRow("Triangle", "Tap at button location")
                    MappingRow("L1 + Circle", "Chip Shot Gesture")
                    MappingRow("R1 + Circle", "Finesse Shot Gesture")
                    MappingRow("L2", "Sprint Modifier")
                    MappingRow("R2", "Shoot / Pass Modifier")
                    MappingRow("D-Pad", "Joystick Flick")
                    MappingRow("Right Stick", "Camera Control")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "Engineering target: minimize end-to-end controller-to-injection latency.\n" +
                        "Actual latency depends on hardware, backend, and Android input pipeline.\n" +
                        "See docs/V1_IMPLEMENTATION_AUDIT.md for details.",
                fontSize = 10.sp,
                color = Color(0xFF616161),
                lineHeight = 14.sp
            )
        }
    }
}

@Composable
fun StatusRow(label: String, active: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .padding(1.dp),
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(4.dp),
                color = if (active) Color(0xFF4CAF50) else Color(0xFFFF5252)
            ) {}
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(label, fontSize = 14.sp, color = Color(0xFFE0E0E0))
        Spacer(modifier = Modifier.weight(1f))
        Text(
            if (active) "Active" else "Inactive",
            fontSize = 12.sp,
            color = if (active) Color(0xFF4CAF50) else Color(0xFF757575)
        )
    }
}

@Composable
fun MappingRow(action: String, target: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(action, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFFBBDEFB))
        Text(target, fontSize = 13.sp, color = Color(0xFF90A4AE))
    }
}
