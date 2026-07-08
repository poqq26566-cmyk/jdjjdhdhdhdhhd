package com.lalakii.androidkeygen

import android.app.DatePickerDialog
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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

    private val createDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/x-pkcs12")) { uri ->
            val bytes = pendingBytes
            if (uri != null && bytes != null) {
                contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                Toast.makeText(this, "证书已保存", Toast.LENGTH_LONG).show()
            }
            pendingBytes = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    KeyGenScreen(
                        onGenerate = { type, years, alias, password, date ->
                            val out = java.io.ByteArrayOutputStream()
                            val error = CertUtils.create(out, type, years, alias, password, date)
                            if (error != null) {
                                Toast.makeText(this, "证书创建失败: $error", Toast.LENGTH_LONG).show()
                            } else {
                                pendingBytes = out.toByteArray()
                                createDocumentLauncher.launch("$alias.jks")
                            }
                        }
                    )
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
