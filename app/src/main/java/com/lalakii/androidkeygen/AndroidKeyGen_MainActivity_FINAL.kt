package com.lalakii.androidkeygen

import android.app.DatePickerDialog
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    // 待写入的证书内容(点击“生成”后暂存,选择保存路径后再落盘)
    private var pendingBytes: ByteArray? = null

    // 查看证书时,用户选中的文件 Uri(选择文件后暂存,供后续读取)
    private var pendingViewUri: Uri? = null
    private var onCertPicked: ((Uri) -> Unit)? = null

    // 签名APK时,用户选中的“未签名APK”和“证书文件”Uri
    private var pendingSignApkUri: Uri? = null
    private var pendingSignKeystoreUri: Uri? = null
    private var onSignApkPicked: ((Uri) -> Unit)? = null
    private var onSignKeystorePicked: ((Uri) -> Unit)? = null

    // 签名完成后的APK文件,暂存于应用缓存目录,供用户选择保存位置
    private var pendingSignedApkFile: java.io.File? = null

    private val createDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/x-pkcs12")) { uri ->
            val bytes = pendingBytes
            if (uri != null && bytes != null) {
                contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                Toast.makeText(this, "证书已保存", Toast.LENGTH_LONG).show()
            }
            pendingBytes = null
        }

    private val openDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                pendingViewUri = uri
                onCertPicked?.invoke(uri)
            }
        }

    private val pickApkLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                pendingSignApkUri = uri
                onSignApkPicked?.invoke(uri)
            }
        }

    private val pickSignKeystoreLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                pendingSignKeystoreUri = uri
                onSignKeystorePicked?.invoke(uri)
            }
        }

    private val saveSignedApkLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.android.package-archive")) { uri ->
            val file = pendingSignedApkFile
            if (uri != null && file != null) {
                contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                Toast.makeText(this, "已签名APK保存成功", Toast.LENGTH_LONG).show()
            }
            pendingSignedApkFile = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    var selectedTab by rememberSaveable { mutableStateOf(0) }

                    Column {
                        TabRow(selectedTabIndex = selectedTab) {
                            Tab(
                                selected = selectedTab == 0,
                                onClick = { selectedTab = 0 },
                                text = { Text("生成证书") }
                            )
                            Tab(
                                selected = selectedTab == 1,
                                onClick = { selectedTab = 1 },
                                text = { Text("查看证书") }
                            )
                            Tab(
                                selected = selectedTab == 2,
                                onClick = { selectedTab = 2 },
                                text = { Text("签名APK") }
                            )
                        }

                        if (selectedTab == 0) {
                            KeyGenScreen(
                                onGenerate = { type, years, alias, password, date ->
                                    val out = java.io.ByteArrayOutputStream()
                                    val error = CertUtils.create(out, type, years, alias, password, date)
                                    if (error != null) {
                                        Toast.makeText(this@MainActivity, "证书创建失败: $error", Toast.LENGTH_LONG).show()
                                    } else {
                                        pendingBytes = out.toByteArray()
                                        createDocumentLauncher.launch("$alias.jks")
                                    }
                                }
                            )
                        } else if (selectedTab == 1) {
                            ViewCertScreen(
                                onPickFile = { onPicked ->
                                    onCertPicked = onPicked
                                    openDocumentLauncher.launch(arrayOf("*/*"))
                                },
                                onReadCert = { password ->
                                    val uri = pendingViewUri
                                    if (uri == null) {
                                        Pair(emptyList(), "请先选择证书文件")
                                    } else {
                                        contentResolver.openInputStream(uri)?.use { input ->
                                            CertUtils.readCertificateInfo(input, password)
                                        } ?: Pair(emptyList(), "无法读取所选文件")
                                    }
                                }
                            )
                        } else {
                            SignApkScreen(
                                onPickApk = { onPicked ->
                                    onSignApkPicked = onPicked
                                    pickApkLauncher.launch(arrayOf("application/vnd.android.package-archive"))
                                },
                                onPickKeystore = { onPicked ->
                                    onSignKeystorePicked = onPicked
                                    pickSignKeystoreLauncher.launch(arrayOf("*/*"))
                                },
                                onSign = { keystorePassword, alias, keyPassword ->
                                    val apkUri = pendingSignApkUri
                                    val ksUri = pendingSignKeystoreUri
                                    if (apkUri == null) {
                                        "请先选择未签名的APK文件"
                                    } else if (ksUri == null) {
                                        "请先选择证书文件"
                                    } else {
                                        try {
                                            val inputApkFile = java.io.File(cacheDir, "input_unsigned.apk")
                                            contentResolver.openInputStream(apkUri)?.use { input ->
                                                inputApkFile.outputStream().use { input.copyTo(it) }
                                            }
                                            val outputApkFile = java.io.File(cacheDir, "output_signed.apk")
                                            if (outputApkFile.exists()) outputApkFile.delete()

                                            var streamOpenError: String? = null
                                            val ksStream = try {
                                                contentResolver.openInputStream(ksUri)
                                            } catch (e: Exception) {
                                                streamOpenError = "打开证书文件失败: ${e.javaClass.simpleName}: ${e.message}"
                                                null
                                            }

                                            val error = when {
                                                streamOpenError != null -> streamOpenError
                                                ksStream == null -> "无法读取证书文件(系统返回了空数据流,可能是文件来源异常)"
                                                else -> ksStream.use { ksInput ->
                                                    ApkSignUtils.signApk(
                                                        inputApkFile,
                                                        outputApkFile,
                                                        ksInput,
                                                        keystorePassword,
                                                        alias.ifBlank { null },
                                                        keyPassword.ifBlank { null }
                                                    )
                                                }
                                            }

                                            if (error == null) {
                                                pendingSignedApkFile = outputApkFile
                                                saveSignedApkLauncher.launch("signed.apk")
                                            }
                                            error
                                        } catch (e: Exception) {
                                            e.message ?: e.toString()
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyGenScreen(
    onGenerate: (AlgorithmType, Int, String, String, Date) -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    var algorithmExpanded by remember { mutableStateOf(false) }
    var selectedAlgorithm by rememberSaveable { mutableStateOf(AlgorithmType.RSA) }

    var yearsExpanded by remember { mutableStateOf(false) }
    var selectedYears by rememberSaveable { mutableStateOf(25) }

    var alias by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }

    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
    var startDate by rememberSaveable { mutableStateOf(dateFormat.format(Date())) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Android KeyGen",
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            text = "生成 Android APK 签名证书(.jks)",
            style = MaterialTheme.typography.bodyMedium
        )

        // 算法选择
        ExposedDropdownMenuBox(
            expanded = algorithmExpanded,
            onExpandedChange = { algorithmExpanded = it }
        ) {
            OutlinedTextField(
                readOnly = true,
                value = selectedAlgorithm.name,
                onValueChange = {},
                label = { Text("算法") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = algorithmExpanded) },
                modifier = Modifier
                    .menuAnchor()
                    .fillMaxWidth()
            )
            DropdownMenu(
                expanded = algorithmExpanded,
                onDismissRequest = { algorithmExpanded = false }
            ) {
                AlgorithmType.values().forEach { algo ->
                    DropdownMenuItem(
                        text = { Text(algo.name) },
                        onClick = {
                            selectedAlgorithm = algo
                            algorithmExpanded = false
                        }
                    )
                }
            }
        }

        // 有效期(年)
        ExposedDropdownMenuBox(
            expanded = yearsExpanded,
            onExpandedChange = { yearsExpanded = it }
        ) {
            OutlinedTextField(
                readOnly = true,
                value = "$selectedYears 年",
                onValueChange = {},
                label = { Text("有效期") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = yearsExpanded) },
                modifier = Modifier
                    .menuAnchor()
                    .fillMaxWidth()
            )
            DropdownMenu(
                expanded = yearsExpanded,
                onDismissRequest = { yearsExpanded = false }
            ) {
                (1..100).forEach { y ->
                    DropdownMenuItem(
                        text = { Text("$y 年") },
                        onClick = {
                            selectedYears = y
                            yearsExpanded = false
                        }
                    )
                }
            }
        }

        // 名称(别名)
        OutlinedTextField(
            value = alias,
            onValueChange = { alias = it },
            label = { Text("名称") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        // 密码
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("密码") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )

        // 起始日期
        OutlinedTextField(
            readOnly = true,
            value = startDate,
            onValueChange = {},
            label = { Text("起始日期") },
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    Modifier
                )
        )
        Button(
            onClick = {
                val cal = Calendar.getInstance()
                val current = try {
                    Calendar.getInstance().apply { time = dateFormat.parse(startDate) ?: Date() }
                } catch (e: Exception) {
                    Calendar.getInstance()
                }
                DatePickerDialog(
                    context,
                    { _, year, month, dayOfMonth ->
                        val picked = Calendar.getInstance()
                        picked.set(year, month, dayOfMonth)
                        startDate = dateFormat.format(picked.time)
                    },
                    current.get(Calendar.YEAR),
                    current.get(Calendar.MONTH),
                    current.get(Calendar.DAY_OF_MONTH)
                ).show()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("选择起始日期")
        }

        Button(
            onClick = {
                if (alias.isBlank() || password.isBlank()) {
                    Toast.makeText(context, "名称或密码不能为空", Toast.LENGTH_SHORT).show()
                    return@Button
                }
                val date = try {
                    dateFormat.parse(startDate) ?: Date()
                } catch (e: Exception) {
                    Date()
                }
                scope.launch {
                    onGenerate(selectedAlgorithm, selectedYears, alias.replace("=", "-"), password, date)
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("生成")
        }
    }
}

@Composable
fun ViewCertScreen(
    onPickFile: ((Uri) -> Unit) -> Unit,
    onReadCert: (String) -> Pair<List<CertInfo>, String?>
) {
    var pickedFileName by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<CertInfo>>(emptyList()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "查看证书信息",
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            text = "选择 .jks / .p12 证书文件,输入密码查看详情",
            style = MaterialTheme.typography.bodyMedium
        )

        Button(
            onClick = {
                onPickFile { uri ->
                    pickedFileName = uri.lastPathSegment ?: "已选择文件"
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("选择证书文件")
        }

        if (pickedFileName.isNotEmpty()) {
            Text(
                text = "已选择:$pickedFileName",
                style = MaterialTheme.typography.bodySmall
            )
        }

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("密码") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = {
                val (info, error) = onReadCert(password)
                results = info
                errorMessage = error
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("查看")
        }

        errorMessage?.let { err ->
            Text(
                text = "读取失败: $err",
                color = MaterialTheme.colorScheme.error
            )
        }

        results.forEach { info ->
            Divider()
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = "别名:${info.alias}", style = MaterialTheme.typography.titleMedium)
                Text(text = "版本:v${info.version}")
                Text(text = "主体(Subject):${info.subject}")
                Text(text = "签发者(Issuer):${info.issuer}")
                Text(text = "是否自签名:${if (info.isSelfSigned) "是" else "否"}")
                Text(text = "是否CA证书:${if (info.isCA) "是" else "否"}")
                Text(text = "序列号:${info.serialNumber}")
                Text(text = "生效时间:${info.notBefore}")
                Text(text = "到期时间:${info.notAfter}")
                Text(text = "签名算法:${info.signatureAlgorithm}")
                Text(text = "公钥算法:${info.publicKeyAlgorithm}")
                Text(
                    text = "公钥位数:" + if (info.publicKeyBits > 0) "${info.publicKeyBits} 位" else "未知"
                )
                Text(text = "SHA-256 指纹:")
                Text(text = info.sha256Fingerprint, style = MaterialTheme.typography.bodySmall)
                Text(text = "SHA-1 指纹:")
                Text(text = info.sha1Fingerprint, style = MaterialTheme.typography.bodySmall)
                Text(text = "MD5 指纹:")
                Text(text = info.md5Fingerprint, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun SignApkScreen(
    onPickApk: ((Uri) -> Unit) -> Unit,
    onPickKeystore: ((Uri) -> Unit) -> Unit,
    onSign: (keystorePassword: String, alias: String, keyPassword: String) -> String?
) {
    var apkFileName by rememberSaveable { mutableStateOf("") }
    var keystoreFileName by rememberSaveable { mutableStateOf("") }
    var keystorePassword by rememberSaveable { mutableStateOf("") }
    var alias by rememberSaveable { mutableStateOf("") }
    var keyPassword by rememberSaveable { mutableStateOf("") }
    var resultMessage by remember { mutableStateOf<String?>(null) }
    var isSigning by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "签名APK",
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            text = "选择未签名的APK和证书文件,完成后选择保存位置",
            style = MaterialTheme.typography.bodyMedium
        )

        Button(
            onClick = { onPickApk { uri -> apkFileName = uri.lastPathSegment ?: "已选择文件" } },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("选择未签名APK")
        }
        if (apkFileName.isNotEmpty()) {
            Text(text = "已选择:$apkFileName", style = MaterialTheme.typography.bodySmall)
        }

        Button(
            onClick = { onPickKeystore { uri -> keystoreFileName = uri.lastPathSegment ?: "已选择文件" } },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("选择证书文件(.jks/.p12)")
        }
        if (keystoreFileName.isNotEmpty()) {
            Text(text = "已选择:$keystoreFileName", style = MaterialTheme.typography.bodySmall)
        }

        OutlinedTextField(
            value = keystorePassword,
            onValueChange = { keystorePassword = it },
            label = { Text("证书密码") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = alias,
            onValueChange = { alias = it },
            label = { Text("别名(留空则自动使用第一个)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = keyPassword,
            onValueChange = { keyPassword = it },
            label = { Text("密钥密码(留空则与证书密码相同)") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = {
                if (keystorePassword.isBlank()) {
                    resultMessage = "请输入证书密码"
                    return@Button
                }
                isSigning = true
                val error = onSign(keystorePassword, alias, keyPassword)
                resultMessage = error ?: "✓ 签名成功,请选择保存位置"
                isSigning = false
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isSigning) "签名中..." else "开始签名")
        }

        resultMessage?.let { msg ->
            Text(
                text = msg,
                color = if (msg.startsWith("✓")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
        }
    }
}
