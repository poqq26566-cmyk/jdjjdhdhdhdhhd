package com.androidcrypt.app

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.provider.OpenableColumns
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.BasicSecureTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.androidcrypt.crypto.VolumeCreator
import com.androidcrypt.crypto.VolumeMountManager
import com.androidcrypt.crypto.EncryptionAlgorithm
import com.androidcrypt.crypto.HashAlgorithm
import com.androidcrypt.crypto.MountedVolumeInfo
import com.androidcrypt.crypto.FAT32Reader
import com.androidcrypt.crypto.FileEntry
import com.androidcrypt.ui.theme.AndroidCryptTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.ByteArrayInputStream
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private const val DEBUG_LOGGING = false

class MainActivity : ComponentActivity() {
    
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            // Handle permission denial
        }
    }
    
    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        // Notification permission granted or denied
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // Prevent screenshots and screen recording of sensitive content
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        
        // SECURITY (H1/L6): we used to auto-redirect the user into the
        // "All files access" Settings screen on every cold start when
        // MANAGE_EXTERNAL_STORAGE was not yet granted.  That granted full
        // shared-storage read/write to the app for the lifetime of the
        // install — and conditioned the user to silently tap through an
        // intrusive permission prompt every launch.  We now rely on the
        // Storage Access Framework (ACTION_OPEN_DOCUMENT / OPEN_DOCUMENT_TREE)
        // for container files and keyfiles, which works without
        // MANAGE_EXTERNAL_STORAGE for any user-picked location.  Users who
        // genuinely want broad-storage browsing can opt in from the Util tab
        // ("Grant All Files Access" button), which still launches the same
        // settings screen but only on explicit request.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        
        // Request notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        
        setContent {
            AndroidCryptTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Unmount all volumes when app is closed
        VolumeMountManager.unmountAll()
        if (DEBUG_LOGGING) Log.d("MainActivity", "All volumes unmounted on app close")
    }
}

@Composable
fun MainScreen() {
    var selectedTab by remember { mutableStateOf(0) }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        ScrollableTabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("打开") }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("创建") }
            )
            Tab(
                selected = selectedTab == 2,
                onClick = { selectedTab = 2 },
                text = { Text("文件管理器") }
            )
            Tab(
                selected = selectedTab == 3,
                onClick = { selectedTab = 3 },
                text = { Text("工具") }
            )
            Tab(
                selected = selectedTab == 4,
                onClick = { selectedTab = 4 },
                text = { Text("使用说明") }
            )
        }
        
        when (selectedTab) {
            0 -> OpenContainerScreen(onNavigateToTab = { selectedTab = it })
            1 -> CreateContainerScreen()
            2 -> FileManagerScreen()
            3 -> UtilScreen()
            4 -> HowToUseScreen()
        }
    }
}

@Composable
fun OpenContainerScreen(onNavigateToTab: (Int) -> Unit = {}) {
    var containerUri by remember { mutableStateOf<Uri?>(null) }
    var containerDisplayName by remember { mutableStateOf("") }
    // C-2: char-buffer-backed password (no String intermediates).
    val passwordState = rememberTextFieldState()
    var pim by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf("") }
    var statusColor by remember { mutableStateOf(Color.Gray) }
    var isMounted by remember { mutableStateOf(false) }
    var volumeInfo by remember { mutableStateOf<MountedVolumeInfo?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var keyfileUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var keyfileNames by remember { mutableStateOf<List<String>>(emptyList()) }
    var useKeyfiles by remember { mutableStateOf(false) }
    var useHiddenVolume by remember { mutableStateOf(false) }
    var useHiddenVolumeProtection by remember { mutableStateOf(false) }
    val hiddenProtPasswordState = rememberTextFieldState()
    var hiddenVolumeExpanded by remember { mutableStateOf(false) }
    
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    // File picker launcher for container
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            // Take persistable permission so we can access the file later
            context.contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            containerUri = it
            // Get display name for the file
            containerDisplayName = context.contentResolver.query(
                it,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: it.lastPathSegment ?: it.toString()
        }
    }
    
    // File picker launcher for keyfiles (can select multiple)
    val keyfilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        val newNames = mutableListOf<String>()
        uris.forEach { uri ->
            // Take persistable permission for each keyfile
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                // Permission might not be persistable, continue anyway
            }
            // Get display name
            val name = context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: uri.lastPathSegment ?: "keyfile"
            newNames.add(name)
        }
        keyfileUris = keyfileUris + uris
        keyfileNames = keyfileNames + newNames
    }
    
    // Check if already mounted
    LaunchedEffect(containerUri) {
        val uriString = containerUri?.toString() ?: ""
        isMounted = VolumeMountManager.isMounted(uriString)
        if (isMounted) {
            volumeInfo = VolumeMountManager.getVolumeReader(uriString)?.volumeInfo
        }
    }
    
    // Also refresh mount status periodically to catch external changes
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            val uriString = containerUri?.toString() ?: ""
            val currentlyMounted = VolumeMountManager.isMounted(uriString)
            if (currentlyMounted != isMounted) {
                isMounted = currentlyMounted
                if (isMounted) {
                    volumeInfo = VolumeMountManager.getVolumeReader(uriString)?.volumeInfo
                }
            }
        }
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 16.dp, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Mount VeraCrypt Container",
            style = MaterialTheme.typography.headlineMedium
        )
        
        // Display selected file
        OutlinedTextField(
            value = containerDisplayName,
            onValueChange = { },
            label = { Text("容器文件") },
            modifier = Modifier.fillMaxWidth(),
            enabled = false,
            placeholder = { Text("选择一个容器文件...") }
        )
        
        OutlinedButton(
            onClick = { 
                filePickerLauncher.launch(arrayOf("*/*"))
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isMounted
        ) {
            Text("浏览...")
        }
        
        SecurePasswordField(
            state = passwordState,
            label = "密码",
            modifier = Modifier.fillMaxWidth(),
            enabled = !isMounted
        )
        
        OutlinedTextField(
            value = pim,
            onValueChange = { pim = it },
            label = { Text("PIM(可选)") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isMounted,
            placeholder = { Text("默认为0") }
        )
        
        // Keyfiles section
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = useKeyfiles,
                onCheckedChange = { useKeyfiles = it },
                enabled = !isMounted
            )
            Text("使用密钥文件")
        }
        
        if (useKeyfiles) {
            OutlinedButton(
                onClick = { 
                    keyfilePickerLauncher.launch(arrayOf("*/*"))
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isMounted
            ) {
                Text("添加密钥文件...")
            }
            
            if (keyfileUris.isNotEmpty()) {
                Text(
                    text = "${keyfileUris.size} keyfile(s) selected",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray
                )
                
                // List keyfiles
                keyfileUris.forEachIndexed { index, uri ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = keyfileNames.getOrElse(index) { uri.lastPathSegment ?: "keyfile" },
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall
                        )
                        TextButton(
                            onClick = { 
                                keyfileUris = keyfileUris.filterIndexed { i, _ -> i != index }
                                keyfileNames = keyfileNames.filterIndexed { i, _ -> i != index }
                            },
                            enabled = !isMounted
                        ) {
                            Text("移除")
                        }
                    }
                }
            }
        }
        
        // Hidden volume section (collapsible)
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { hiddenVolumeExpanded = !hiddenVolumeExpanded }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Hidden Volume",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Icon(
                imageVector = if (hiddenVolumeExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (hiddenVolumeExpanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.primary
            )
        }
        
        AnimatedVisibility(visible = hiddenVolumeExpanded) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = useHiddenVolume,
                        onCheckedChange = { 
                            useHiddenVolume = it
                            if (it) useHiddenVolumeProtection = false  // mutually exclusive
                        },
                        enabled = !isMounted
                    )
                    Text("挂载隐藏卷(使用上方的隐藏卷密码)")
                }
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = useHiddenVolumeProtection,
                        onCheckedChange = { 
                            useHiddenVolumeProtection = it
                            if (it) useHiddenVolume = false  // mutually exclusive
                        },
                        enabled = !isMounted
                    )
                    Column {
                        Text("挂载外层卷时保护隐藏卷")
                        Text(
                            text = "Prevents outer volume writes from damaging hidden data",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                if (useHiddenVolumeProtection) {
                    SecurePasswordField(
                        state = hiddenProtPasswordState,
                        label = "隐藏卷密码(用于保护)",
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isMounted,
                        supportingText = "Enter the hidden volume password so the app can locate and protect its data area"
                    )
                }
            }
        }
        
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        
        if (!isMounted) {
            Button(
                onClick = {
                    if (containerUri == null) {
                        statusMessage = "Please select a container file"
                        statusColor = Color.Red
                        return@Button
                    }
                    if (passwordState.text.isEmpty() && keyfileUris.isEmpty()) {
                        statusMessage = "请输入密码或选择密钥文件"
                        statusColor = Color.Red
                        return@Button
                    }
                    
                    if (useHiddenVolumeProtection && hiddenProtPasswordState.text.isEmpty()) {
                        statusMessage = "请输入用于保护的隐藏卷密码"
                        statusColor = Color.Red
                        return@Button
                    }
                    
                    statusMessage = if (useHiddenVolume) "Mounting hidden volume..." 
                                    else if (useHiddenVolumeProtection) "Mounting outer volume with hidden volume protection..."
                                    else "Mounting container..."
                    statusColor = Color.Blue
                    isLoading = true
                    
                    scope.launch {
                        // C-2: extract chars directly from the secure buffer; never round-trip through String.
                        val passwordChars = passwordState.toCharArrayCopy()
                        val hiddenProtChars = if (useHiddenVolumeProtection) hiddenProtPasswordState.toCharArrayCopy() else null
                        val result = try {
                            withContext(Dispatchers.IO) {
                                val pimValue = pim.toIntOrNull() ?: 0
                                VolumeMountManager.mountVolumeFromUri(
                                    context = context,
                                    uri = containerUri!!,
                                    password = passwordChars,
                                    pim = pimValue,
                                    keyfileUris = if (useKeyfiles) keyfileUris else emptyList(),
                                    useHiddenVolume = useHiddenVolume,
                                    hiddenVolumeProtectionPassword = hiddenProtChars
                                )
                            }
                        } finally {
                            passwordChars.fill('\u0000')
                            hiddenProtChars?.fill('\u0000')
                        }
                        
                        isLoading = false
                        result.fold(
                            onSuccess = { info ->
                                volumeInfo = info
                                isMounted = true
                                // C-2: zero secure buffers in place after successful mount.
                                passwordState.clearText()
                                pim = ""
                                hiddenProtPasswordState.clearText()
                                val volumeType = when {
                                    info.isHiddenVolume -> "🔒 Encrypted volume"
                                    info.outerVolumeProtectedSize > 0 -> "📦 Encrypted volume (write-protected)"
                                    else -> "📦 Encrypted volume"
                                }
                                statusMessage = "✓ Successfully mounted!\n" +
                                    "Type: $volumeType\n" +
                                    "Data area: ${info.getDataAreaSizeMB()} MB\n" +
                                    "\n📁 Volume is now accessible to other apps through:\n" +
                                    "Files app → ☰ Menu → VeraCrypt Volume"
                                statusColor = Color(0xFF4CAF50)
                                
                                // Navigate to file manager tab
                                onNavigateToTab(2)
                            },
                            onFailure = { e ->
                                statusMessage = "挂载容器失败:\n${e.message}"
                                statusColor = Color.Red
                            }
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text("挂载容器")
            }
        } else {
            // Mounted state - show unmount button
            Button(
                onClick = {
                    scope.launch {
                        val uriString = containerUri?.toString() ?: ""
                        val result = withContext(Dispatchers.IO) {
                            VolumeMountManager.unmountVolume(uriString)
                        }
                        
                        result.fold(
                            onSuccess = {
                                isMounted = false
                                volumeInfo = null
                                statusMessage = "容器已成功卸载"
                                statusColor = Color.Gray
                            },
                            onFailure = { e ->
                                statusMessage = "卸载失败:\n${e.message}"
                                statusColor = Color.Red
                            }
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("卸载")
            }
        }
        
        if (statusMessage.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = when (statusColor) {
                        Color.Red -> Color(0xFFFFEBEE)
                        Color(0xFF4CAF50) -> Color(0xFFE8F5E9)
                        else -> MaterialTheme.colorScheme.secondaryContainer
                    }
                )
            ) {
                Text(
                    text = statusMessage,
                    modifier = Modifier.padding(16.dp),
                    color = statusColor
                )
            }
        }
        
        // Link to How to Use tab
        TextButton(
            onClick = { onNavigateToTab(4) },
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = "使用说明",
                modifier = Modifier
                    .size(18.dp)
                    .padding(end = 4.dp)
            )
            Text(
                text = "如何使用 AndroidCrypt",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
fun CreateContainerScreen() {
    var containerPath by remember { mutableStateOf("") }
    // C-2: char-buffer-backed passwords (no String intermediates).
    val passwordState = rememberTextFieldState()
    val confirmPasswordState = rememberTextFieldState()
    var containerSize by remember { mutableStateOf("10") }
    var pim by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf("") }
    var isCreating by remember { mutableStateOf(false) }
    var keyfileUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var keyfileNames by remember { mutableStateOf<List<String>>(emptyList()) }
    var useKeyfiles by remember { mutableStateOf(false) }
    var selectedAlgorithm by remember { mutableStateOf(EncryptionAlgorithm.AES) }
    var algorithmDropdownExpanded by remember { mutableStateOf(false) }
    var selectedHashAlgorithm by remember { mutableStateOf(HashAlgorithm.SHA512) }
    var hashDropdownExpanded by remember { mutableStateOf(false) }
    
    // Hidden volume state
    var createHiddenVolume by remember { mutableStateOf(false) }
    val hiddenPasswordState = rememberTextFieldState()
    val confirmHiddenPasswordState = rememberTextFieldState()
    var hiddenVolumeSize by remember { mutableStateOf("") }
    var hiddenVolumeExpanded by remember { mutableStateOf(false) }
    
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    // File picker launcher for keyfiles (can select multiple)
    val keyfilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        val newNames = mutableListOf<String>()
        uris.forEach { uri ->
            // Take persistable permission for each keyfile
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                // Permission might not be persistable, continue anyway
            }
            // Get display name
            val name = context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: uri.lastPathSegment ?: "keyfile"
            newNames.add(name)
        }
        keyfileUris = keyfileUris + uris
        keyfileNames = keyfileNames + newNames
    }
    
    // Suggest default path on first load
    LaunchedEffect(Unit) {
        if (containerPath.isEmpty()) {
            containerPath = "/storage/emulated/0/Download/mycontainer.hc"
        }
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 16.dp, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Create New Container",
            style = MaterialTheme.typography.headlineMedium
        )
        
        OutlinedTextField(
            value = containerPath,
            onValueChange = { containerPath = it },
            label = { Text("容器文件路径") },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("/storage/emulated/0/mycontainer.hc") }
        )
        
        OutlinedTextField(
            value = containerSize,
            onValueChange = { 
                // Only allow digits
                if (it.isEmpty() || it.all { char -> char.isDigit() }) {
                    containerSize = it
                }
            },
            label = { Text("容器大小(MB)") },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("10") }
        )
        
        SecurePasswordField(
            state = passwordState,
            label = "密码",
            modifier = Modifier.fillMaxWidth(),
            supportingText = "Can be empty if using keyfiles"
        )
        
        SecurePasswordField(
            state = confirmPasswordState,
            label = "确认密码",
            modifier = Modifier.fillMaxWidth()
        )
        
        OutlinedTextField(
            value = pim,
            onValueChange = { pim = it },
            label = { Text("PIM(可选)") },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("默认为0") }
        )
        
        // Encryption algorithm selector
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = selectedAlgorithm.algorithmName,
                onValueChange = {},
                readOnly = true,
                label = { Text("加密算法") },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !isCreating) { algorithmDropdownExpanded = true },
                enabled = false,
                colors = OutlinedTextFieldDefaults.colors(
                    disabledTextColor = MaterialTheme.colorScheme.onSurface,
                    disabledBorderColor = MaterialTheme.colorScheme.outline,
                    disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
            DropdownMenu(
                expanded = algorithmDropdownExpanded,
                onDismissRequest = { algorithmDropdownExpanded = false }
            ) {
                EncryptionAlgorithm.entries.forEach { algo ->
                    DropdownMenuItem(
                        text = { Text(algo.algorithmName) },
                        onClick = {
                            selectedAlgorithm = algo
                            algorithmDropdownExpanded = false
                        }
                    )
                }
            }
        }
        
        // Hash algorithm selector — controls the PBKDF2 PRF used to derive the
        // header-encryption key.  All five VeraCrypt PRFs are wired up:
        // SHA-512 (default), SHA-256 use the JCE provider; Whirlpool, Blake2s,
        // and Streebog go through the bundled native PBKDF2 implementation.
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = selectedHashAlgorithm.algorithmName,
                onValueChange = {},
                readOnly = true,
                label = { Text("哈希算法(PBKDF2)") },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !isCreating) { hashDropdownExpanded = true },
                enabled = false,
                colors = OutlinedTextFieldDefaults.colors(
                    disabledTextColor = MaterialTheme.colorScheme.onSurface,
                    disabledBorderColor = MaterialTheme.colorScheme.outline,
                    disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
            DropdownMenu(
                expanded = hashDropdownExpanded,
                onDismissRequest = { hashDropdownExpanded = false }
            ) {
                HashAlgorithm.entries.forEach { hash ->
                    DropdownMenuItem(
                        text = { Text(hash.algorithmName) },
                        onClick = {
                            selectedHashAlgorithm = hash
                            hashDropdownExpanded = false
                        }
                    )
                }
            }
        }
        
        // Keyfiles section
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = useKeyfiles,
                onCheckedChange = { useKeyfiles = it },
                enabled = !isCreating
            )
            Text("使用密钥文件")
        }
        
        if (useKeyfiles) {
            OutlinedButton(
                onClick = { 
                    keyfilePickerLauncher.launch(arrayOf("*/*"))
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isCreating
            ) {
                Text("添加密钥文件...")
            }
            
            if (keyfileUris.isNotEmpty()) {
                Text(
                    text = "${keyfileUris.size} keyfile(s) selected",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray
                )
                
                // List keyfiles
                keyfileUris.forEachIndexed { index, uri ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = keyfileNames.getOrElse(index) { uri.lastPathSegment ?: "keyfile" },
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall
                        )
                        TextButton(
                            onClick = { 
                                keyfileUris = keyfileUris.filterIndexed { i, _ -> i != index }
                                keyfileNames = keyfileNames.filterIndexed { i, _ -> i != index }
                            },
                            enabled = !isCreating
                        ) {
                            Text("移除")
                        }
                    }
                }
            }
        }
        
        // Hidden volume section (collapsible)
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { hiddenVolumeExpanded = !hiddenVolumeExpanded }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Hidden Volume",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Icon(
                imageVector = if (hiddenVolumeExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (hiddenVolumeExpanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.primary
            )
        }
        
        AnimatedVisibility(visible = hiddenVolumeExpanded) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = createHiddenVolume,
                        onCheckedChange = { createHiddenVolume = it },
                        enabled = !isCreating
                    )
                    Column {
                        Text("在此容器内创建隐藏卷")
                        Text(
                            text = "Two-step process: first creates the outer volume, then embeds a hidden volume inside it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                if (createHiddenVolume) {
                    SecurePasswordField(
                        state = hiddenPasswordState,
                        label = "隐藏卷密码",
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isCreating,
                        supportingText = "Must be different from the outer volume password"
                    )
                    
                    SecurePasswordField(
                        state = confirmHiddenPasswordState,
                        label = "确认隐藏卷密码",
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isCreating
                    )
                    
                    OutlinedTextField(
                        value = hiddenVolumeSize,
                        onValueChange = { 
                            if (it.isEmpty() || it.all { char -> char.isDigit() }) {
                                hiddenVolumeSize = it
                            }
                        },
                        label = { Text("隐藏卷大小(MB)") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isCreating,
                        placeholder = { Text("必须小于外层容器") },
                        supportingText = {
                            val outerMB = containerSize.toLongOrNull() ?: 0
                            val maxHidden = if (outerMB > 0) outerMB - 1 else 0
                            Text("最大约 ${maxHidden} MB(外层容器:${outerMB} MB)")
                        }
                    )
                    
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "⚠️ Hidden Volume Info",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "The hidden volume is undetectable without its password. " +
                                    "The outer volume password reveals only the outer data. " +
                                    "When mounting the outer volume, enable 'Protect hidden volume' " +
                                    "to prevent writes from overwriting hidden data.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
        
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        
        Button(
            onClick = {
                if (confirmPasswordState.text.isNotEmpty() && !passwordState.contentEqualsState(confirmPasswordState)) {
                    statusMessage = "错误:两次密码不一致!"
                    return@Button
                }
                if (passwordState.text.isEmpty() && keyfileUris.isEmpty()) {
                    statusMessage = "错误:需要密码或密钥文件!"
                    return@Button
                }
                
                val sizeMB = containerSize.toLongOrNull()
                if (sizeMB == null || sizeMB < 1) {
                    statusMessage = "错误:容器大小无效!"
                    return@Button
                }
                
                // Validate hidden volume parameters
                if (createHiddenVolume) {
                    if (hiddenPasswordState.text.isEmpty()) {
                        statusMessage = "错误:隐藏卷需要密码!"
                        return@Button
                    }
                    if (confirmHiddenPasswordState.text.isNotEmpty() && !hiddenPasswordState.contentEqualsState(confirmHiddenPasswordState)) {
                        statusMessage = "错误:隐藏卷两次密码不一致!"
                        return@Button
                    }
                    if (hiddenPasswordState.contentEqualsState(passwordState)) {
                        statusMessage = "错误:隐藏卷密码必须与外层卷密码不同!"
                        return@Button
                    }
                    val hiddenMB = hiddenVolumeSize.toLongOrNull()
                    if (hiddenMB == null || hiddenMB < 1) {
                        statusMessage = "错误:隐藏卷大小无效!"
                        return@Button
                    }
                    if (hiddenMB >= sizeMB) {
                        statusMessage = "错误:隐藏卷必须小于外层容器!"
                        return@Button
                    }
                }
                
                isCreating = true
                statusMessage = if (createHiddenVolume) 
                    "Creating container with hidden volume... Please wait."
                else 
                    "Creating container... Please wait."
                
                scope.launch {
                    try {
                        // Step 1: Create the outer container
                        // C-2: extract chars directly from the secure buffer.
                        val passwordChars = passwordState.toCharArrayCopy()
                        val result = try {
                            withContext(Dispatchers.IO) {
                                val pimValue = pim.toIntOrNull() ?: 0
                                VolumeCreator.createContainer(
                                    containerPath = containerPath,
                                    password = passwordChars,
                                    sizeInMB = sizeMB,
                                    pim = pimValue,
                                    keyfileUris = if (useKeyfiles) keyfileUris else emptyList(),
                                    context = context,
                                    algorithm = selectedAlgorithm,
                                    hashAlgorithm = selectedHashAlgorithm
                                )
                            }
                        } finally {
                            passwordChars.fill('\u0000')
                        }
                        
                        if (result.isFailure) {
                            statusMessage = "Error: ${result.exceptionOrNull()?.message}"
                            isCreating = false
                            return@launch
                        }
                        
                        // Step 2: Create hidden volume inside the outer container (if requested)
                        if (createHiddenVolume) {
                            statusMessage = "Outer container created. Creating hidden volume..."
                            
                            val outerChars = passwordState.toCharArrayCopy()
                            val hiddenChars = hiddenPasswordState.toCharArrayCopy()
                            val hiddenResult = try {
                                withContext(Dispatchers.IO) {
                                    val pimValue = pim.toIntOrNull() ?: 0
                                    VolumeCreator.createHiddenVolume(
                                        containerPath = containerPath,
                                        outerPassword = outerChars,
                                        hiddenPassword = hiddenChars,
                                        hiddenSizeInMB = hiddenVolumeSize.toLongOrNull() ?: 1,
                                        pim = pimValue,
                                        keyfileUris = if (useKeyfiles) keyfileUris else emptyList(),
                                        context = context,
                                        algorithm = selectedAlgorithm,
                                        hashAlgorithm = selectedHashAlgorithm
                                    )
                                }
                            } finally {
                                outerChars.fill('\u0000')
                                hiddenChars.fill('\u0000')
                            }
                            
                            statusMessage = hiddenResult.getOrElse { e ->
                                "Outer container created but hidden volume failed: ${e.message}"
                            }
                        } else {
                            statusMessage = result.getOrElse { e ->
                                "Error: ${e.message}"
                            }
                        }
                    } catch (e: Exception) {
                        statusMessage = "Error: ${e.message}"
                    } finally {
                        isCreating = false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = containerPath.isNotEmpty() && (passwordState.text.isNotEmpty() || keyfileUris.isNotEmpty()) && !isCreating
        ) {
            if (isCreating) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(if (isCreating) "Creating..." else "Create Container")
        }
        
        if (statusMessage.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (statusMessage.contains("success", ignoreCase = true))
                        MaterialTheme.colorScheme.primaryContainer
                    else if (statusMessage.contains("error", ignoreCase = true) || 
                             statusMessage.contains("!"))
                        MaterialTheme.colorScheme.errorContainer
                    else
                        MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Text(
                    text = statusMessage,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
        
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Encryption Settings",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Text("算法:${selectedAlgorithm.algorithmName}", style = MaterialTheme.typography.bodyMedium)
                Text("模式:XTS", style = MaterialTheme.typography.bodyMedium)
                Text("哈希:${selectedHashAlgorithm.algorithmName}", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
fun UtilScreen() {
    var statusMessage by remember { mutableStateOf("") }
    var statusColor by remember { mutableStateOf(Color.Gray) }
    val scope = rememberCoroutineScope()
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Utilities",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Volume Management",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                Text(
                    text = "Use this button to unmount all currently mounted volumes. This is useful for clearing stale volumes or ensuring all volumes are properly closed.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                VolumeMountManager.unmountAll()
                            }
                            statusMessage = "所有卷已成功卸载"
                            statusColor = Color(0xFF4CAF50)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "全部卸载",
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text("卸载所有卷")
                }
            }
        }
        
        if (statusMessage.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Text(
                    text = statusMessage,
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = statusColor
                )
            }
        }
    }
}

@Composable
fun HowToUseScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 16.dp, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "如何使用 AndroidCrypt",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        
        // Creating a Container Section
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "📦 第一步是创建一个加密文件容器",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                
                Text(
                    text = "1. 前往「创建」标签页",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                Text(
                    text = "2. 点击「选择位置」,选择容器文件的保存位置",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                Text(
                    text = "3. 输入容器大小(单位:MB)",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                Text(
                    text = "4. 输入强密码并确认",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                Text(
                    text = "5.(可选)添加密钥文件以增强安全性",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                Text(
                    text = "6. 点击「创建容器」并等待完成",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
        
        // Mounting a Container Section
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "🔓 打开(挂载)容器",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                
                Text(
                    text = "1. 前往「打开」标签页",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                Text(
                    text = "2. 点击「选择容器」,选择你的加密文件",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                Text(
                    text = "3. 输入密码",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                Text(
                    text = "4. 如果使用了密钥文件,请一并添加",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                Text(
                    text = "5. 点击「挂载容器」",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                Text(
                    text = "6. 通过系统文件管理器或内置文件管理标签页访问文件",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                Text(
                    text = "7. 使用完毕后,点击「卸载」以关闭容器",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
        
        // File Size Recommendations
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "💡 容器大小建议",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                
                Text(
                    text = "小型(10-100 MB)",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Text(
                    text = "适合:文本文档、密码、小文件",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                
                Text(
                    text = "中型(100-500 MB)",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Text(
                    text = "适合:照片、PDF、办公文档",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                
                Text(
                    text = "大型(500-2000 MB)",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Text(
                    text = "适合:相册、音乐合集",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                
                Text(
                    text = "超大型(2000+ MB)",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Text(
                    text = "适合:视频文件、大型归档",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        
        // Important Notes
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "⚠️ 重要提示",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                
                Text(
                    text = "• 切勿忘记密码——没有密码找回功能",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                Text(
                    text = "• 妥善保管密钥文件——访问数据时需要用到",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                Text(
                    text = "• 使用完毕后务必卸载容器",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                Text(
                    text = "• 兼容 VeraCrypt 桌面端加密文件容器",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }
}

@Composable
fun FileManagerScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    // Mounted volume state
    var mountedVolumes by remember { mutableStateOf(VolumeMountManager.getMountedVolumes()) }
    var selectedVolume by remember { mutableStateOf<String?>(null) }
    var volumeInfo by remember { mutableStateOf<MountedVolumeInfo?>(null) }
    var usedSpace by remember { mutableStateOf(0L) }
    var freeSpace by remember { mutableStateOf(0L) }
    var totalSpace by remember { mutableStateOf(0L) }
    
    // UI state
    var statusMessage by remember { mutableStateOf("") }
    var statusColor by remember { mutableStateOf(Color.Gray) }
    var isLoading by remember { mutableStateOf(false) }
    var copyProgress by remember { mutableStateOf("") }
    var isCopying by remember { mutableStateOf(false) }
    
    // Export state - for selecting files/folders from volume
    var showFilePickerDialog by remember { mutableStateOf(false) }
    var showFolderPickerDialog by remember { mutableStateOf(false) }
    var volumeFiles by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var currentPath by remember { mutableStateOf("/") }
    var selectedExportFile by remember { mutableStateOf<FileEntry?>(null) }
    var selectedExportFolder by remember { mutableStateOf<FileEntry?>(null) }
    
    // Destination picker for exporting a file from volume to device
    val exportFileDestinationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { destUri ->
        if (destUri != null && selectedVolume != null && selectedExportFile != null) {
            val fileToExport = selectedExportFile!!
            scope.launch {
                isCopying = true
                copyProgress = "Exporting: ${fileToExport.name}"
                statusMessage = ""
                
                try {
                    val reader = VolumeMountManager.getOrCreateFileSystemReader(selectedVolume!!)
                    if (reader != null) {
                        withContext(Dispatchers.IO) {
                            exportFileFromVolume(context, reader, fileToExport, destUri)
                        }
                        
                        statusMessage = "✓ 文件导出成功!"
                        statusColor = Color(0xFF4CAF50)
                    }
                } catch (e: Exception) {
                    if (DEBUG_LOGGING) Log.e("FileManager", "Failed to export file", e)
                    statusMessage = "✗ Export failed: ${e.message}"
                    statusColor = Color.Red
                } finally {
                    isCopying = false
                    copyProgress = ""
                    selectedExportFile = null
                }
            }
        }
    }
    
    // Destination picker for exporting a folder from volume to device
    val exportFolderDestinationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { destUri ->
        if (destUri != null && selectedVolume != null && selectedExportFolder != null) {
            val folderToExport = selectedExportFolder!!
            scope.launch {
                isCopying = true
                copyProgress = "Counting files..."
                statusMessage = ""
                
                try {
                    val reader = VolumeMountManager.getOrCreateFileSystemReader(selectedVolume!!)
                    if (reader != null) {
                        withContext(Dispatchers.IO) {
                            // Count files in the folder first
                            val totalFiles = countFilesInVolumeFolder(reader, folderToExport.path)
                            val counter = CopyCounter(totalFiles)
                            
                            exportFolderFromVolume(context, reader, folderToExport, destUri, counter) { progress ->
                                copyProgress = progress
                            }
                        }
                        
                        statusMessage = "✓ 文件夹导出成功!"
                        statusColor = Color(0xFF4CAF50)
                    }
                } catch (e: Exception) {
                    if (DEBUG_LOGGING) Log.e("FileManager", "Failed to export folder", e)
                    statusMessage = "✗ Export failed: ${e.message}"
                    statusColor = Color.Red
                } finally {
                    isCopying = false
                    copyProgress = ""
                    selectedExportFolder = null
                }
            }
        }
    }
    
    // File picker launcher for copying single files from device
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null && selectedVolume != null) {
            // Take persistent permission for the file
            try {
                val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, takeFlags)
            } catch (e: Exception) {
                // Some URIs don't support persistent permissions
            }
            
            val intent = Intent(context, CopyService::class.java).apply {
                action = CopyService.ACTION_COPY_FILE_TO_VOLUME
                putExtra(CopyService.EXTRA_SOURCE_URI, uri)
                putExtra(CopyService.EXTRA_VOLUME_PATH, selectedVolume)
                clipData = ClipData.newRawUri("", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            
            isCopying = true
            copyProgress = "Starting copy service..."
            statusMessage = ""
        }
    }
    
    // State that drives the custom in-app folder browser.
    // ACTION_OPEN_DOCUMENT_TREE (SAF) cannot be used here because Android 11+ hard-codes
    // certain directories (Downloads root, storage root, SD card root, etc.) as unselectable
    // regardless of permissions — that is what produces "can't use this folder".  Since the
    // app holds MANAGE_EXTERNAL_STORAGE we can browse the real file system directly instead.
    var showDeviceFolderPicker by remember { mutableStateOf(false) }
    
    // Observe CopyService state
    val copyState by CopyService.copyState.collectAsStateWithLifecycle()
    val serviceProgress by CopyService.progress.collectAsStateWithLifecycle()
    val isServiceRunning by CopyService.isRunning.collectAsStateWithLifecycle()
    
    // React to service state changes
    LaunchedEffect(copyState) {
        when (val state = copyState) {
            is CopyService.CopyState.Copying -> {
                isCopying = true
                copyProgress = state.progress
            }
            is CopyService.CopyState.Completed -> {
                isCopying = false
                copyProgress = ""
                statusMessage = "✓ ${state.message}"
                statusColor = Color(0xFF4CAF50)
                
                // Refresh space stats (efficient — no recursive traversal)
                if (selectedVolume != null) {
                    scope.launch(Dispatchers.IO) {
                        val reader = VolumeMountManager.getOrCreateFileSystemReader(selectedVolume!!)
                        if (reader != null) {
                            totalSpace = reader.getTotalSpaceBytes()
                            val freeClusters = reader.countFreeClusters()
                            val clusterSize = reader.getClusterSize()
                            freeSpace = freeClusters.toLong() * clusterSize
                            usedSpace = totalSpace - freeSpace
                        }
                    }
                }
            }
            is CopyService.CopyState.Error -> {
                isCopying = false
                copyProgress = ""
                statusMessage = "✗ ${state.message}"
                statusColor = Color.Red
            }
            is CopyService.CopyState.Idle -> {
                // Service is idle
            }
        }
    }
    
    // Refresh mounted volumes
    LaunchedEffect(Unit) {
        mountedVolumes = VolumeMountManager.getMountedVolumes()
        if (mountedVolumes.isNotEmpty() && selectedVolume == null) {
            selectedVolume = mountedVolumes.first()
        }
    }
    
    // Get volume info when volume is selected
    LaunchedEffect(selectedVolume) {
        selectedVolume?.let { volumePath ->
            isLoading = true
            val volumeReader = VolumeMountManager.getVolumeReader(volumePath)
            volumeInfo = volumeReader?.volumeInfo
            
            // Calculate space stats efficiently from FAT metadata (no recursive traversal)
            if (volumeReader != null) {
                withContext(Dispatchers.IO) {
                    try {
                        val reader = VolumeMountManager.getOrCreateFileSystemReader(volumePath)
                        if (reader != null) {
                            totalSpace = reader.getTotalSpaceBytes()
                            val freeClusters = reader.countFreeClusters()
                            val clusterSize = reader.getClusterSize()
                            freeSpace = freeClusters.toLong() * clusterSize
                            usedSpace = totalSpace - freeSpace
                        }
                    } catch (e: Exception) {
                        if (DEBUG_LOGGING) Log.e("FileManager", "Failed to get volume stats", e)
                    }
                }
            }
            isLoading = false
        }
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "File Manager",
            style = MaterialTheme.typography.headlineMedium
        )
        
        // Status message
        if (statusMessage.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = when (statusColor) {
                        Color.Red -> Color(0xFFFFEBEE)
                        Color(0xFF4CAF50) -> Color(0xFFE8F5E9)
                        else -> MaterialTheme.colorScheme.secondaryContainer
                    }
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = statusMessage,
                        modifier = Modifier.weight(1f),
                        color = statusColor
                    )
                    IconButton(
                        onClick = { statusMessage = "" },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "关闭",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
        
        if (mountedVolumes.isEmpty()) {
            // No volume mounted
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "No Volume Mounted",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Open and mount a VeraCrypt container first to access files.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            // Volume is mounted - show info and open button
            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else {
                // Volume Info Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFFE8F5E9)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = Color(0xFF4CAF50),
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Volume Mounted",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color(0xFF4CAF50)
                                )
                            }
                            
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        selectedVolume?.let { volume ->
                                            withContext(Dispatchers.IO) {
                                                VolumeMountManager.unmountVolume(volume)
                                            }
                                            mountedVolumes = VolumeMountManager.getMountedVolumes()
                                            selectedVolume = null
                                            volumeInfo = null
                                            statusMessage = "卷已成功卸载"
                                            statusColor = Color.Gray
                                        }
                                    }
                                },
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                ),
                                modifier = Modifier.height(36.dp)
                            ) {
                                Text(
                                    text = "Unmount",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        volumeInfo?.let { info ->
                            Text(
                                text = "Total Size: ${info.totalSize / (1024 * 1024)} MB",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.Black
                            )
                            Text(
                                text = "Data Area: ${info.getDataAreaSizeMB()} MB",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.Black
                            )
                            
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            // Storage usage progress bar
                            val usageProgress = if (totalSpace > 0) {
                                (usedSpace.toFloat() / totalSpace.toFloat()).coerceIn(0f, 1f)
                            } else 0f
                            
                            Text(
                                text = "Storage Usage",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.Black
                            )
                            
                            Spacer(modifier = Modifier.height(4.dp))
                            
                            LinearProgressIndicator(
                                progress = { usageProgress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                color = when {
                                    usageProgress > 0.9f -> Color(0xFFF44336) // Red when nearly full
                                    usageProgress > 0.75f -> Color(0xFFFF9800) // Orange when getting full
                                    else -> Color(0xFF4CAF50) // Green otherwise
                                },
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                            
                            Spacer(modifier = Modifier.height(4.dp))
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Used: ${formatFileSize(usedSpace)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.Black
                                )
                                Text(
                                    text = "Free: ${formatFileSize(freeSpace)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.Black
                                )
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Open in Files App button
                Button(
                    onClick = {
                        try {
                            // Build URI for our DocumentsProvider root
                            val authority = "com.androidcrypt.documents"
                            val rootId = "veracrypt_${selectedVolume?.hashCode()}"
                            val documentId = "$rootId:/"
                            
                            // Create URI to open in document browser
                            val rootUri = DocumentsContract.buildRootUri(authority, rootId)
                            val documentUri = DocumentsContract.buildDocumentUri(authority, documentId)
                            
                            // Try ACTION_VIEW first (opens Files app directly to location)
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(documentUri, DocumentsContract.Document.MIME_TYPE_DIR)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                            }
                            
                            // Check if there's an app that can handle this
                            if (intent.resolveActivity(context.packageManager) != null) {
                                context.startActivity(intent)
                            } else {
                                // Fallback: Open document picker (user can navigate to VeraCrypt Volume in sidebar)
                                val fallbackIntent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                                    putExtra(DocumentsContract.EXTRA_INITIAL_URI, documentUri)
                                }
                                context.startActivity(fallbackIntent)
                                
                                statusMessage = "Look for 'VeraCrypt Volume' in the sidebar (☰ menu)"
                                statusColor = Color(0xFF2196F3)
                            }
                        } catch (e: Exception) {
                            if (DEBUG_LOGGING) Log.e("FileManager", "Failed to open Files app", e)
                            statusMessage = "Could not open Files app: ${e.message}"
                            statusColor = Color.Red
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.List,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("在文件应用中打开")
                }
                
                // Copy folder from device button
                Button(
                    onClick = { showDeviceFolderPicker = true },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isCopying,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF1565C0)
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("从设备复制文件夹")
                }
                
                // Show copy progress
                if (isCopying) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFFE3F2FD)
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = copyProgress,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.Black,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            TextButton(
                                onClick = {
                                    // Cancel the copy service
                                    val intent = Intent(context, CopyService::class.java).apply {
                                        action = CopyService.ACTION_CANCEL
                                    }
                                    context.startService(intent)
                                },
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = Color.Red
                                )
                            ) {
                                Text("取消复制")
                            }
                        }
                    }
                }
                
                // Copy single file from device to volume
                Button(
                    onClick = {
                        filePickerLauncher.launch(arrayOf("*/*"))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isCopying,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF1565C0)
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("从设备复制文件")
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Copy file from volume to device
                Button(
                    onClick = { showFilePickerDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isCopying,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF42A5F5)
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("复制文件到设备")
                }
                
                // Copy folder from volume to device
                Button(
                    onClick = { showFolderPickerDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isCopying,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF42A5F5)
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("复制文件夹到设备")
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Instructions card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "📁 How to Copy Files",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        Text(
                            text = "To copy files to/from your encrypted volume:",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Text(
                            text = "1. Open your device's Files app",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "2. Tap the ☰ menu in the corner",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "3. Select 'VeraCrypt Volume' from the sidebar",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "4. Use Copy/Paste or drag-and-drop as usual",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        Text(
                            text = "💡 The volume appears as a storage location in any app that uses Android's file picker!",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
    
    // Dialog for selecting a file from the volume to export
    if (showFilePickerDialog) {
        val volumeReader = selectedVolume?.let { VolumeMountManager.getVolumeReader(it) }
        if (volumeReader != null) {
            val fsReader = selectedVolume?.let { VolumeMountManager.getOrCreateFileSystemReader(it) }
            if (fsReader != null) {
                LaunchedEffect(currentPath) {
                    withContext(Dispatchers.IO) {
                        volumeFiles = fsReader.listDirectory(currentPath).getOrDefault(emptyList())
                    }
                }
            }
        }
        
        AlertDialog(
            onDismissRequest = { 
                showFilePickerDialog = false
                currentPath = "/"
            },
            title = { Text("选择要导出的文件") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (currentPath != "/") {
                        TextButton(onClick = {
                            currentPath = currentPath.substringBeforeLast('/').ifEmpty { "/" }
                        }) {
                            Text("⬆️ 返回上级")
                        }
                    }
                    
                    Text("当前路径: $currentPath", style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    LazyColumn(modifier = Modifier.height(300.dp)) {
                        items(volumeFiles) { file ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (file.isDirectory) {
                                            currentPath = file.path
                                        } else {
                                            selectedExportFile = file
                                            showFilePickerDialog = false
                                            currentPath = "/"
                                            exportFileDestinationLauncher.launch(null)
                                        }
                                    }
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(if (file.isDirectory) "📁" else "📄")
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(file.name)
                                    if (!file.isDirectory) {
                                        Text(
                                            formatFileSize(file.size),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color.Gray
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { 
                    showFilePickerDialog = false
                    currentPath = "/"
                }) {
                    Text("取消")
                }
            }
        )
    }
    
    // Dialog for selecting a folder from the volume to export
    if (showFolderPickerDialog) {
        val volumeReader = selectedVolume?.let { VolumeMountManager.getVolumeReader(it) }
        if (volumeReader != null) {
            val fsReader = selectedVolume?.let { VolumeMountManager.getOrCreateFileSystemReader(it) }
            if (fsReader != null) {
                LaunchedEffect(currentPath) {
                    withContext(Dispatchers.IO) {
                        volumeFiles = fsReader.listDirectory(currentPath).getOrDefault(emptyList())
                    }
                }
            }
        }
        
        AlertDialog(
            onDismissRequest = { 
                showFolderPickerDialog = false
                currentPath = "/"
            },
            title = { Text("选择要导出的文件夹") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (currentPath != "/") {
                        TextButton(onClick = {
                            currentPath = currentPath.substringBeforeLast('/').ifEmpty { "/" }
                        }) {
                            Text("⬆️ 返回上级")
                        }
                    }
                    
                    Text("当前路径: $currentPath", style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    LazyColumn(modifier = Modifier.height(300.dp)) {
                        items(volumeFiles.filter { it.isDirectory }) { folder ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        currentPath = folder.path
                                    }
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("📁")
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(folder.name)
                            }
                        }
                    }
                    
                    if (currentPath != "/") {
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                // Create a FileEntry for the current folder
                                val folderName = currentPath.substringAfterLast('/')
                                selectedExportFolder = FileEntry(
                                    name = folderName,
                                    path = currentPath,
                                    isDirectory = true,
                                    size = 0,
                                    lastModified = System.currentTimeMillis()
                                )
                                showFolderPickerDialog = false
                                currentPath = "/"
                                exportFolderDestinationLauncher.launch(null)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("导出此文件夹")
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { 
                    showFolderPickerDialog = false
                    currentPath = "/"
                }) {
                    Text("取消")
                }
            }
        )
    }

    // In-app folder browser — bypasses SAF restrictions
    if (showDeviceFolderPicker) {
        DeviceFolderPickerDialog(
            onDismiss = { showDeviceFolderPicker = false },
            onFolderSelected = { selectedDir ->
                showDeviceFolderPicker = false
                val vol = selectedVolume ?: return@DeviceFolderPickerDialog
                val intent = Intent(context, CopyService::class.java).apply {
                    action = CopyService.ACTION_COPY_FOLDER_PATH_TO_VOLUME
                    putExtra(CopyService.EXTRA_SOURCE_PATH, selectedDir.absolutePath)
                    putExtra(CopyService.EXTRA_VOLUME_PATH, vol)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                isCopying = true
                copyProgress = "Starting copy service..."
                statusMessage = ""
            }
        )
    }
}

// Helper functions



private fun formatFileSize(size: Long): String {
    return when {
        size < 1024 -> "$size B"
        size < 1024 * 1024 -> "${size / 1024} KB"
        size < 1024 * 1024 * 1024 -> "${size / (1024 * 1024)} MB"
        else -> "${size / (1024 * 1024 * 1024)} GB"
    }
}

/**
 * Get folder name from a document tree URI
 */
private fun getFolderNameFromUri(context: android.content.Context, uri: Uri): String {
    // For tree URIs from OpenDocumentTree, use getTreeDocumentId
    val docId = try {
        DocumentsContract.getDocumentId(uri)
    } catch (e: Exception) {
        DocumentsContract.getTreeDocumentId(uri)
    }
    return docId.substringAfterLast('/').substringAfterLast(':').ifEmpty { "copied_folder" }
}

/**
 * Counter for tracking copy progress
 */
private class CopyCounter(val total: Int) {
    var current: Int = 0
    
    fun increment(): Int {
        current++
        return current
    }
    
    fun progressString(): String = "$current/$total"
}

/**
 * Count all files recursively in a folder
 */
private fun countFilesInFolder(context: android.content.Context, folderUri: Uri): Int {
    var count = 0
    // For tree URIs, try getDocumentId first, fall back to getTreeDocumentId
    val docId = try {
        DocumentsContract.getDocumentId(folderUri)
    } catch (e: Exception) {
        DocumentsContract.getTreeDocumentId(folderUri)
    }
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(folderUri, docId)
    
    val cursor = context.contentResolver.query(
        childrenUri,
        arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        ),
        null, null, null
    )
    
    cursor?.use {
        while (it.moveToNext()) {
            val docId = it.getString(0)
            val mimeType = it.getString(1)
            val isDirectory = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
            
            if (isDirectory) {
                count += countFilesInSubFolder(context, folderUri, docId)
            } else {
                count++
            }
        }
    }
    
    return count
}

/**
 * Count files in a subfolder recursively
 */
private fun countFilesInSubFolder(context: android.content.Context, treeUri: Uri, folderId: String): Int {
    var count = 0
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, folderId)
    
    val cursor = context.contentResolver.query(
        childrenUri,
        arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        ),
        null, null, null
    )
    
    cursor?.use {
        while (it.moveToNext()) {
            val docId = it.getString(0)
            val mimeType = it.getString(1)
            val isDirectory = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
            
            if (isDirectory) {
                count += countFilesInSubFolder(context, treeUri, docId)
            } else {
                count++
            }
        }
    }
    
    return count
}

/**
 * Data class to hold pre-read file information for pipelined copying
 */
private data class PreReadFile(
    val name: String,
    val targetPath: String,
    val data: ByteArray,
    val size: Long
)

/**
 * Recursively copy a folder from device to the encrypted volume using pipelined I/O
 */
private suspend fun copyFolderToVolume(
    context: android.content.Context,
    folderUri: Uri,
    targetPath: String,
    folderName: String,
    reader: FAT32Reader,
    counter: CopyCounter,
    onProgress: (String) -> Unit
): Unit = coroutineScope {
    // Create the folder in the volume
    val newFolderPath = if (targetPath == "/") "/$folderName" else "$targetPath/$folderName"
    
    // Check if folder already exists, if not create it
    if (!reader.exists(newFolderPath)) {
        reader.createDirectory(targetPath, folderName).getOrThrow()
        if (DEBUG_LOGGING) Log.d("FileManager", "Created folder: $newFolderPath")
    }
    
    // Get the document URI for the folder - handle both tree and document URIs
    val docId = try {
        DocumentsContract.getDocumentId(folderUri)
    } catch (e: Exception) {
        DocumentsContract.getTreeDocumentId(folderUri)
    }
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(folderUri, docId)
    
    // Collect all files and subdirectories
    val files: MutableList<Triple<String, String, Long>> = mutableListOf() // docId, name, size
    val subdirs: MutableList<Pair<String, String>> = mutableListOf() // docId, name
    
    // Query children
    val cursor = context.contentResolver.query(
        childrenUri,
        arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
        ),
        null, null, null
    )
    
    cursor?.use {
        while (it.moveToNext()) {
            val docId = it.getString(0)
            val name = it.getString(1)
            val mimeType = it.getString(2)
            val size = it.getLong(3)
            
            val isDirectory = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
            
            if (isDirectory) {
                subdirs.add(docId to name)
            } else {
                // Check if file already exists in volume
                val filePath = if (newFolderPath == "/") "/$name" else "$newFolderPath/$name"
                if (reader.exists(filePath)) {
                    counter.increment()
                    onProgress("Skipping ${counter.progressString()}: $name (exists)")
                } else {
                    files.add(Triple(docId, name, size))
                }
            }
        }
    }
    
    // Use a channel for pipelined file reading/writing
    // Larger buffer (8 files) allows more overlap between reading and writing
    val fileChannel = Channel<PreReadFile>(capacity = 8)
    
    // Producer: Read multiple files in parallel for faster I/O
    // Use semaphore to limit concurrent reads (avoid memory pressure)
    val readSemaphore = kotlinx.coroutines.sync.Semaphore(4) // 4 concurrent reads
    val producer = launch(Dispatchers.IO) {
        // Launch parallel readers for all files
        val readJobs: List<Job> = files.map { (docId, name, size) ->
            launch {
                readSemaphore.acquire()
                try {
                    val fileUri = DocumentsContract.buildDocumentUriUsingTree(folderUri, docId)
                    val inputStream = context.contentResolver.openInputStream(fileUri)
                    if (inputStream != null) {
                        val data = inputStream.use { it.readBytes() }
                        fileChannel.send(PreReadFile(name, newFolderPath, data, size))
                    }
                } catch (e: Exception) {
                    if (DEBUG_LOGGING) Log.e("FileManager", "Failed to pre-read file: $name", e)
                } finally {
                    readSemaphore.release()
                }
            }
        }
        // Wait for all reads to complete
        readJobs.forEach { it.join() }
        fileChannel.close()
    }
    
    // Consumer: Write files as they become available
    // Files were already checked for existence in the producer loop, so we can skip the check here
    for (preRead in fileChannel) {
        counter.increment()
        onProgress("Copying ${counter.progressString()}: ${preRead.name}")
        
        val newFilePath = if (preRead.targetPath == "/") "/${preRead.name}" else "${preRead.targetPath}/${preRead.name}"
        
        // Create file entry (we know it doesn't exist because we filtered in producer)
        reader.createFile(preRead.targetPath, preRead.name).getOrThrow()
        
        // Write using streaming with pre-read data
        val inputStream = ByteArrayInputStream(preRead.data)
        reader.writeFileStreaming(newFilePath, inputStream, preRead.data.size.toLong(), null).getOrThrow()
    }
    
    // Wait for producer to finish
    producer.join()
    
    // Process subdirectories (can't parallelize these due to FAT32 structure requirements)
    for ((docId, name) in subdirs) {
        copySubFolder(context, folderUri, docId, newFolderPath, name, reader, counter, onProgress)
    }
}

/**
 * Copy a subfolder recursively with pipelined I/O
 */
private suspend fun copySubFolder(
    context: android.content.Context,
    treeUri: Uri,
    folderId: String,
    targetPath: String,
    folderName: String,
    reader: FAT32Reader,
    counter: CopyCounter,
    onProgress: (String) -> Unit
): Unit = coroutineScope {
    // Create the folder in the volume
    val newFolderPath = if (targetPath == "/") "/$folderName" else "$targetPath/$folderName"
    
    if (!reader.exists(newFolderPath)) {
        reader.createDirectory(targetPath, folderName).getOrThrow()
        if (DEBUG_LOGGING) Log.d("FileManager", "Created subfolder: $newFolderPath")
    }
    
    // Collect all files and subdirectories
    val files: MutableList<Triple<String, String, Long>> = mutableListOf() // docId, name, size
    val subdirs: MutableList<Pair<String, String>> = mutableListOf() // docId, name
    
    // Query children of this subfolder
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, folderId)
    
    val cursor = context.contentResolver.query(
        childrenUri,
        arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
        ),
        null, null, null
    )
    
    cursor?.use {
        while (it.moveToNext()) {
            val docId = it.getString(0)
            val name = it.getString(1)
            val mimeType = it.getString(2)
            val size = it.getLong(3)
            
            val isDirectory = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
            
            if (isDirectory) {
                subdirs.add(docId to name)
            } else {
                // Check if file already exists in volume
                val filePath = if (newFolderPath == "/") "/$name" else "$newFolderPath/$name"
                if (reader.exists(filePath)) {
                    counter.increment()
                    onProgress("Skipping ${counter.progressString()}: $name (exists)")
                } else {
                    files.add(Triple(docId, name, size))
                }
            }
        }
    }
    
    // Use a channel for pipelined file reading/writing
    val fileChannel = Channel<PreReadFile>(capacity = 8)
    
    // Producer: Read multiple files in parallel
    val readSemaphore = kotlinx.coroutines.sync.Semaphore(4)
    val producer = launch(Dispatchers.IO) {
        val readJobs: List<Job> = files.map { (docId, name, size) ->
            launch {
                readSemaphore.acquire()
                try {
                    val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    val inputStream = context.contentResolver.openInputStream(fileUri)
                    if (inputStream != null) {
                        val data = inputStream.use { it.readBytes() }
                        fileChannel.send(PreReadFile(name, newFolderPath, data, size))
                    }
                } catch (e: Exception) {
                    if (DEBUG_LOGGING) Log.e("FileManager", "Failed to pre-read file: $name", e)
                } finally {
                    readSemaphore.release()
                }
            }
        }
        readJobs.forEach { it.join() }
        fileChannel.close()
    }
    
    // Consumer: Write files as they become available
    // Files were already checked for existence in the producer loop
    for (preRead in fileChannel) {
        counter.increment()
        onProgress("Copying ${counter.progressString()}: ${preRead.name}")
        
        val newFilePath = if (preRead.targetPath == "/") "/${preRead.name}" else "${preRead.targetPath}/${preRead.name}"
        
        // Create file entry (we know it doesn't exist because we filtered in producer)
        reader.createFile(preRead.targetPath, preRead.name).getOrThrow()
        
        // Write using streaming with pre-read data
        val inputStream = ByteArrayInputStream(preRead.data)
        reader.writeFileStreaming(newFilePath, inputStream, preRead.data.size.toLong(), null).getOrThrow()
    }
    
    // Wait for producer to finish
    producer.join()
    
    // Process subdirectories
    for ((docId, name) in subdirs) {
        copySubFolder(context, treeUri, docId, newFolderPath, name, reader, counter, onProgress)
    }
}

/**
 * Get the display name of a file from its URI
 */
private fun getFileNameFromUri(context: android.content.Context, uri: Uri): String {
    var fileName: String? = null
    
    context.contentResolver.query(
        uri,
        arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
        null, null, null
    )?.use { cursor ->
        if (cursor.moveToFirst()) {
            fileName = cursor.getString(0)
        }
    }
    
    return fileName ?: uri.lastPathSegment ?: "unknown_file"
}

/**
 * Copy a single file to the encrypted volume using streaming for better performance
 */
private fun copyFileToVolume(
    context: android.content.Context,
    fileUri: Uri,
    targetPath: String,
    fileName: String,
    reader: FAT32Reader
) {
    // Get file size first
    val fileSize = context.contentResolver.query(
        fileUri,
        arrayOf(DocumentsContract.Document.COLUMN_SIZE),
        null, null, null
    )?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getLong(0) else -1L
    } ?: -1L
    
    // Create file in volume
    val newFilePath = if (targetPath == "/") "/$fileName" else "$targetPath/$fileName"
    
    // Check if file exists, create if not
    if (!reader.exists(newFilePath)) {
        reader.createFile(targetPath, fileName).getOrThrow()
        // Force directory re-read to ensure the new entry is visible
        // This is needed when a new cluster was allocated for the directory
        reader.listDirectory(targetPath).getOrThrow()
    }
    
    // Use streaming write for better performance with large files
    if (fileSize > 0) {
        val inputStream = context.contentResolver.openInputStream(fileUri)
            ?: throw Exception("Cannot open file: $fileName")
        
        inputStream.use { stream ->
            reader.writeFileStreaming(newFilePath, stream, fileSize, null).getOrThrow()
        }
    } else {
        // Fallback to regular write if size unknown
        val inputStream = context.contentResolver.openInputStream(fileUri)
            ?: throw Exception("Cannot open file: $fileName")
        
        val fileBytes = inputStream.use { it.readBytes() }
        reader.writeFile(newFilePath, fileBytes).getOrThrow()
    }
}

/**
 * Export a single file from the encrypted volume to the device
 */
private fun exportFileFromVolume(
    context: android.content.Context,
    reader: FAT32Reader,
    fileEntry: FileEntry,
    destFolderUri: Uri
) {
    // Read file content from volume
    val fileBytes = reader.readFile(fileEntry.path).getOrThrow()
    
    // Create the file in the destination folder - handle both tree and document URIs
    val destDocId = try {
        DocumentsContract.getDocumentId(destFolderUri)
    } catch (e: Exception) {
        DocumentsContract.getTreeDocumentId(destFolderUri)
    }
    val destDocUri = DocumentsContract.buildDocumentUriUsingTree(destFolderUri, destDocId)
    
    val newFileUri = DocumentsContract.createDocument(
        context.contentResolver,
        destDocUri,
        "application/octet-stream",
        fileEntry.name
    ) ?: throw Exception("Failed to create file: ${fileEntry.name}")
    
    // Write content to the new file
    context.contentResolver.openOutputStream(newFileUri)?.use { outputStream ->
        outputStream.write(fileBytes)
    } ?: throw Exception("Failed to write to file: ${fileEntry.name}")
}

/**
 * Count files in a volume folder recursively
 */
private fun countFilesInVolumeFolder(reader: FAT32Reader, folderPath: String): Int {
    var count = 0
    val entries = reader.listDirectory(folderPath).getOrDefault(emptyList())
    for (entry in entries) {
        if (entry.isDirectory) {
            count += countFilesInVolumeFolder(reader, entry.path)
        } else {
            count++
        }
    }
    return count
}

/**
 * Export a folder from the encrypted volume to the device
 */
private fun exportFolderFromVolume(
    context: android.content.Context,
    reader: FAT32Reader,
    folderEntry: FileEntry,
    destFolderUri: Uri,
    counter: CopyCounter,
    onProgress: (String) -> Unit
) {
    // Create the folder in the destination - handle both tree and document URIs
    val destDocId = try {
        DocumentsContract.getDocumentId(destFolderUri)
    } catch (e: Exception) {
        DocumentsContract.getTreeDocumentId(destFolderUri)
    }
    val destDocUri = DocumentsContract.buildDocumentUriUsingTree(destFolderUri, destDocId)
    
    val newFolderUri = DocumentsContract.createDocument(
        context.contentResolver,
        destDocUri,
        DocumentsContract.Document.MIME_TYPE_DIR,
        folderEntry.name
    ) ?: throw Exception("Failed to create folder: ${folderEntry.name}")
    
    // Get the new folder's document ID for creating children
    val newFolderDocId = DocumentsContract.getDocumentId(newFolderUri)
    val newFolderTreeUri = DocumentsContract.buildDocumentUriUsingTree(destFolderUri, newFolderDocId)
    
    // List and export contents
    val entries = reader.listDirectory(folderEntry.path).getOrDefault(emptyList())
    for (entry in entries) {
        if (entry.isDirectory) {
            exportSubFolderFromVolume(context, reader, entry, destFolderUri, newFolderDocId, counter, onProgress)
        } else {
            // Check if file already exists in destination
            val existingFile = findFileInFolder(context, newFolderTreeUri, entry.name)
            if (existingFile != null) {
                counter.increment()
                onProgress("Skipping ${counter.progressString()}: ${entry.name} (exists)")
            } else {
                // Export file
                counter.increment()
                onProgress("Copying ${counter.progressString()}: ${entry.name}")
                
                val fileBytes = reader.readFile(entry.path).getOrThrow()
                
                val newFileUri = DocumentsContract.createDocument(
                    context.contentResolver,
                    newFolderTreeUri,
                    "application/octet-stream",
                    entry.name
                ) ?: throw Exception("Failed to create file: ${entry.name}")
                
                context.contentResolver.openOutputStream(newFileUri)?.use { outputStream ->
                    outputStream.write(fileBytes)
                } ?: throw Exception("Failed to write to file: ${entry.name}")
            }
        }
    }
}

/**
 * Export a subfolder from the encrypted volume to the device
 */
private fun exportSubFolderFromVolume(
    context: android.content.Context,
    reader: FAT32Reader,
    folderEntry: FileEntry,
    treeUri: Uri,
    parentDocId: String,
    counter: CopyCounter,
    onProgress: (String) -> Unit
) {
    // Create the folder
    val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId)
    
    val newFolderUri = DocumentsContract.createDocument(
        context.contentResolver,
        parentDocUri,
        DocumentsContract.Document.MIME_TYPE_DIR,
        folderEntry.name
    ) ?: throw Exception("Failed to create folder: ${folderEntry.name}")
    
    val newFolderDocId = DocumentsContract.getDocumentId(newFolderUri)
    val newFolderDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, newFolderDocId)
    
    // List and export contents
    val entries = reader.listDirectory(folderEntry.path).getOrDefault(emptyList())
    for (entry in entries) {
        if (entry.isDirectory) {
            exportSubFolderFromVolume(context, reader, entry, treeUri, newFolderDocId, counter, onProgress)
        } else {
            // Check if file already exists in destination
            val existingFile = findFileInFolder(context, newFolderDocUri, entry.name)
            if (existingFile != null) {
                counter.increment()
                onProgress("Skipping ${counter.progressString()}: ${entry.name} (exists)")
            } else {
                // Export file
                counter.increment()
                onProgress("Copying ${counter.progressString()}: ${entry.name}")
                
                val fileBytes = reader.readFile(entry.path).getOrThrow()
                
                val newFileUri = DocumentsContract.createDocument(
                    context.contentResolver,
                    newFolderDocUri,
                    "application/octet-stream",
                    entry.name
                ) ?: throw Exception("Failed to create file: ${entry.name}")
                
                context.contentResolver.openOutputStream(newFileUri)?.use { outputStream ->
                    outputStream.write(fileBytes)
                } ?: throw Exception("Failed to write to file: ${entry.name}")
            }
        }
    }
}

/**
 * Find a file by name in a folder
 */
private fun findFileInFolder(context: android.content.Context, folderUri: Uri, fileName: String): Uri? {
    val docId = DocumentsContract.getDocumentId(folderUri)
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(folderUri, docId)
    
    context.contentResolver.query(
        childrenUri,
        arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME
        ),
        null, null, null
    )?.use { cursor ->
        while (cursor.moveToNext()) {
            val name = cursor.getString(1)
            if (name == fileName) {
                val childDocId = cursor.getString(0)
                return DocumentsContract.buildDocumentUriUsingTree(folderUri, childDocId)
            }
        }
    }
    return null
}
/**
 * Custom in-app folder browser that uses java.io.File directly.
 *
 * Android 11+ hard-codes certain directories (Downloads root, storage root, SD card root)
 * as unselectable in ACTION_OPEN_DOCUMENT_TREE regardless of the app's permissions — this
 * produces the "Can't use this folder" error in the system picker.  Since the app holds
 * MANAGE_EXTERNAL_STORAGE we can walk the real file system without going through SAF at all.
 */
@Composable
private fun DeviceFolderPickerDialog(
    onDismiss: () -> Unit,
    onFolderSelected: (java.io.File) -> Unit
) {
    var currentDir by remember {
        mutableStateOf(android.os.Environment.getExternalStorageDirectory())
    }

    // Sorted list of sub-directories in the current directory
    val subDirs = remember(currentDir) {
        currentDir.listFiles()
            ?.filter { it.isDirectory && !it.isHidden }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = currentDir.absolutePath,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2
            )
        },
        text = {
            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier.fillMaxWidth()
            ) {
                // ".." row — navigate to parent
                val storageRoot = android.os.Environment.getExternalStorageDirectory()
                if (currentDir.absolutePath != storageRoot.absolutePath &&
                    currentDir.parentFile != null
                ) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { currentDir = currentDir.parentFile!! }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowBack,
                                contentDescription = "返回上级",
                                modifier = Modifier.size(20.dp),
                                tint = Color(0xFF1565C0)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("..", style = MaterialTheme.typography.bodyMedium)
                        }
                        HorizontalDivider()
                    }
                }

                if (subDirs.isEmpty()) {
                    item {
                        Text(
                            text = "(no sub-folders)",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                }

                items(subDirs) { dir ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Tap the folder name/icon area to select this folder for copying
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onFolderSelected(dir) }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = Color(0xFFFFA000)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(dir.name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "Tap to select",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.Gray
                                )
                            }
                        }
                        // Chevron navigates INTO this folder
                        IconButton(onClick = { currentDir = dir }) {
                            Icon(
                                imageVector = Icons.Default.ArrowForward,
                                contentDescription = "打开文件夹",
                                tint = Color(0xFF1565C0)
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = {
            Button(onClick = { onFolderSelected(currentDir) }) {
                Text("复制 \"${currentDir.name}\"")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

// =============================================================================
// C-2: Secure password input
//
// Compose's stock OutlinedTextField stores its value as an immutable `String`.
// Every keystroke creates a NEW String on the JVM heap; the old ones can never
// be zeroed and remain in memory until garbage-collected (and even then their
// underlying char[] backing array can persist in a memory dump).
//
// `BasicSecureTextField` (Foundation 1.7+) backs its state with a mutable
// `TextFieldBuffer` (gap buffer of chars), not Strings. `TextFieldState.text`
// is a live `CharSequence` view of that buffer. `clearText()` overwrites the
// buffer in place. The only Strings that touch the heap are transient IME
// deltas, which is the irreducible minimum on Android.
//
// `SecurePasswordField` wraps it in a Material3-styled outlined container so
// the visual language matches the rest of the form.
// =============================================================================

@Composable
fun SecurePasswordField(
    state: TextFieldState,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    supportingText: String? = null,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (enabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 1.dp,
                    color = if (enabled) MaterialTheme.colorScheme.outline
                            else MaterialTheme.colorScheme.outline.copy(alpha = 0.38f),
                    shape = RoundedCornerShape(4.dp)
                )
                .padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
            BasicSecureTextField(
                state = state,
                enabled = enabled,
                textStyle = LocalTextStyle.current.copy(
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    autoCorrect = false
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (supportingText != null) {
            Text(
                text = supportingText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp)
            )
        }
    }
}

/**
 * Copy the secure buffer's chars into a fresh CharArray *without* going
 * through `String`. Caller is responsible for zero-filling the returned
 * array via `.fill('\u0000')` when finished.
 */
fun TextFieldState.toCharArrayCopy(): CharArray {
    val src: CharSequence = this.text
    val out = CharArray(src.length)
    for (i in 0 until src.length) out[i] = src[i]
    return out
}

/** Content-based equality between two secure password buffers. */
fun TextFieldState.contentEqualsState(other: TextFieldState): Boolean {
    val a: CharSequence = this.text
    val b: CharSequence = other.text
    if (a.length != b.length) return false
    for (i in a.indices) if (a[i] != b[i]) return false
    return true
}