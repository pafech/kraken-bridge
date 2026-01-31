package com.krakenbridge

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private var connectionStatus by mutableStateOf("disconnected")
    private var statusMessage by mutableStateOf("Not connected")
    private var accessibilityEnabled by mutableStateOf(false)
    private var showHelpDialog by mutableStateOf(false)

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let {
                connectionStatus = it.getStringExtra(KrakenBleService.EXTRA_STATUS) ?: "unknown"
                statusMessage = it.getStringExtra(KrakenBleService.EXTRA_MESSAGE) ?: ""
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            startConnection()
        } else {
            Toast.makeText(this, "Bluetooth permissions required", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContent {
            KrakenBridgeTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        status = connectionStatus,
                        message = statusMessage,
                        accessibilityEnabled = accessibilityEnabled,
                        showHelpDialog = showHelpDialog,
                        onConnect = { checkPermissionsAndConnect() },
                        onDisconnect = { stopConnection() },
                        onOpenCamera = { openGoogleCamera() },
                        onEnableAccessibility = { openAccessibilitySettings() },
                        onShowHelp = { showHelpDialog = true },
                        onDismissHelp = { showHelpDialog = false }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(KrakenBleService.BROADCAST_STATUS)
        registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
        
        // Check accessibility service status
        accessibilityEnabled = isAccessibilityServiceEnabled()
    }
    
    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        return enabledServices.any { 
            it.resolveInfo.serviceInfo.packageName == packageName &&
            it.resolveInfo.serviceInfo.name == KrakenAccessibilityService::class.java.name
        }
    }
    
    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
        Toast.makeText(this, "Enable \"Kraken Bridge\" accessibility service", Toast.LENGTH_LONG).show()
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(statusReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver not registered
        }
    }

    private fun checkPermissionsAndConnect() {
        val permissions = mutableListOf<String>()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isEmpty()) {
            startConnection()
        } else {
            permissionLauncher.launch(notGranted.toTypedArray())
        }
    }

    private fun startConnection() {
        val intent = Intent(this, KrakenBleService::class.java).apply {
            action = KrakenBleService.ACTION_CONNECT
        }
        startForegroundService(intent)
    }

    private fun stopConnection() {
        val intent = Intent(this, KrakenBleService::class.java).apply {
            action = KrakenBleService.ACTION_DISCONNECT
        }
        startService(intent)
    }

    private fun openGoogleCamera() {
        try {
            // Try to open Google Camera specifically
            val intent = Intent(Intent.ACTION_MAIN).apply {
                setPackage("com.google.android.GoogleCamera")
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            startActivity(intent)
        } catch (e: Exception) {
            // Fallback to any camera app
            try {
                val intent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
                startActivity(intent)
            } catch (e2: Exception) {
                Toast.makeText(this, "Could not open camera", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

@Composable
fun KrakenBridgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF00BCD4),
            secondary = Color(0xFF03A9F4),
            background = Color(0xFF121212),
            surface = Color(0xFF1E1E1E),
            onPrimary = Color.White,
            onSecondary = Color.White,
            onBackground = Color.White,
            onSurface = Color.White
        ),
        content = content
    )
}

@Composable
fun MainScreen(
    status: String,
    message: String,
    accessibilityEnabled: Boolean,
    showHelpDialog: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenCamera: () -> Unit,
    onEnableAccessibility: () -> Unit,
    onShowHelp: () -> Unit,
    onDismissHelp: () -> Unit
) {
    // Show help dialog when requested
    if (showHelpDialog) {
        HelpDialog(onDismiss = onDismissHelp)
    }
    val isConnected = status == "connected" || status == "ready"
    val isConnecting = status == "scanning" || status == "connecting" || status == "reconnecting"
    
    val statusColor = when (status) {
        "ready" -> Color(0xFF4CAF50)
        "connected" -> Color(0xFF8BC34A)
        "scanning", "connecting" -> Color(0xFFFFC107)
        "reconnecting" -> Color(0xFFFF9800) // Orange for reconnecting
        "error" -> Color(0xFFF44336)
        else -> Color(0xFF9E9E9E)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Spacer(modifier = Modifier.height(32.dp))
        
        // Title
        Text(
            text = "Kraken Bridge",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        
        Text(
            text = "BLE to Camera Bridge",
            fontSize = 16.sp,
            color = Color.Gray
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Accessibility Service Status
        if (!accessibilityEnabled) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF3E2723)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "⚠️ Accessibility Required",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFFAB00)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Enable the accessibility service to allow button presses to control camera apps",
                        fontSize = 14.sp,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = onEnableAccessibility,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFAB00)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Enable Accessibility", color = Color.Black)
                    }
                }
            }
        }
        
        // Status indicator
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Status dot
                Surface(
                    modifier = Modifier.size(24.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = statusColor
                ) {}
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    text = status.replaceFirstChar { it.uppercase() },
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium
                )
                
                Text(
                    text = message,
                    fontSize = 14.sp,
                    color = Color.Gray,
                    textAlign = TextAlign.Center
                )
                
                if (accessibilityEnabled) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "✓ Accessibility enabled",
                        fontSize = 12.sp,
                        color = Color(0xFF4CAF50)
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.weight(1f))
        
        // Help button
        OutlinedButton(
            onClick = onShowHelp,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("? Button Mapping", fontSize = 16.sp)
        }
        
        Spacer(modifier = Modifier.weight(1f))
        
        // Action buttons
        if (!isConnected && !isConnecting) {
            Button(
                onClick = onConnect,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Connect to Kraken", fontSize = 18.sp)
            }
        } else if (isConnecting) {
            Button(
                onClick = onDisconnect,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFC107))
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = Color.Black,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text("Cancel", fontSize = 18.sp, color = Color.Black)
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onOpenCamera,
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Open Camera", fontSize = 16.sp)
                }
                
                Button(
                    onClick = onDisconnect,
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF44336))
                ) {
                    Text("Disconnect", fontSize = 16.sp)
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
fun ButtonMappingRow(button: String, action: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = button, color = Color.Gray, fontSize = 14.sp)
        Text(text = action, fontSize = 14.sp)
    }
}

@Composable
fun HelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
        title = {
            Text(
                text = "Button Mapping",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                // Camera Mode
                Text(
                    text = "Camera Mode",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = Color(0xFF4CAF50)
                )
                Spacer(modifier = Modifier.height(8.dp))
                HelpRow("Shutter (red)", "Take photo / Record video")
                HelpRow("Fn", "Toggle Photo ↔ Video mode")
                HelpRow("Plus (+)", "Focus closer")
                HelpRow("Minus (-)", "Focus farther")
                HelpRow("OK", "Auto-focus (center)")
                HelpRow("Back", "Switch to Gallery")
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Gallery Mode
                Text(
                    text = "Gallery Mode",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = Color(0xFF2196F3)
                )
                Spacer(modifier = Modifier.height(8.dp))
                HelpRow("Plus (+)", "Next photo/video")
                HelpRow("Minus (-)", "Previous photo/video")
                HelpRow("OK", "Delete photo/video")
                HelpRow("Back / Fn / Shutter", "Return to Camera")
            }
        },
        containerColor = MaterialTheme.colorScheme.surface
    )
}

@Composable
fun HelpRow(button: String, action: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = button,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = action,
            fontSize = 14.sp,
            color = Color.Gray,
            modifier = Modifier.weight(1.5f),
            textAlign = TextAlign.End
        )
    }
}
