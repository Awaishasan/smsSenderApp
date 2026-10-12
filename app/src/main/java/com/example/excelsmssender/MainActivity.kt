package com.example.excelsmssender

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.gyanoba.kexcel.Excel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SmsSenderScreen()
        }
    }
}

@Composable
fun SmsSenderScreen() {
    val context = LocalContext.current
    var queueState by remember {
        mutableStateOf(SmsQueueStore.load(context) ?: SmsQueueState.create(emptyList()))
    }
    var selectedFileName by remember { mutableStateOf("No file selected") }
    var errorMessage by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var queueSizeInput by remember { mutableStateOf(queueState.queueSize.toString()) }
    var confirmRetry by remember { mutableStateOf(false) }
    var recipientsConsented by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun startService(action: String, includeQueue: Boolean = false) {
        val intent = Intent(context, SmsQueueService::class.java).setAction(action)
        if (includeQueue) {
            intent.putExtra(
                SmsQueueService.EXTRA_QUEUE_JSON,
                SmsQueueStore.toServiceJson(queueState).toString()
            )
        }
        ContextCompat.startForegroundService(context, intent)
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // Android still runs a foreground service if notification permission is denied,
        // although notification visibility is then controlled by the system.
        startService(SmsQueueService.ACTION_START, includeQueue = true)
    }

    val smsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            val hasNotificationPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            if (hasNotificationPermission) {
                startService(SmsQueueService.ACTION_START, includeQueue = true)
            } else {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            Toast.makeText(context, "SMS permission denied; no messages were sent", Toast.LENGTH_LONG).show()
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedFileName = "Reading selected Excel file..."
            errorMessage = ""
            isLoading = true
            scope.launch {
                try {
                    val result = withContext(Dispatchers.IO) { readExcelFile(context, uri) }
                    queueState = SmsQueueState.create(result, queueState.queueSize)
                    recipientsConsented = false
                    queueSizeInput = queueState.queueSize.toString()
                    SmsQueueStore.save(context, queueState)
                    selectedFileName = "Excel loaded successfully"
                } catch (e: Exception) {
                    Log.e("ExcelReader", "FAILED TO READ EXCEL FILE", e)
                    messagesError(e).also { errorMessage = it }
                    selectedFileName = "Excel file could not be loaded"
                } finally {
                    isLoading = false
                }
            }
        }
    }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                val json = intent.getStringExtra(SmsQueueService.EXTRA_QUEUE_JSON) ?: return
                try {
                    queueState = SmsQueueStore.fromServiceJson(JSONObject(json))
                    queueSizeInput = queueState.queueSize.toString()
                    SmsQueueStore.save(context, queueState)
                } catch (e: Exception) {
                    Log.e("SmsSender", "Could not read queue update from service", e)
                    errorMessage = "Could not refresh SMS queue status"
                }
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(SmsQueueService.ACTION_STATE_UPDATED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose { context.unregisterReceiver(receiver) }
    }

    LaunchedEffect(context) {
        try {
            context.startService(
                Intent(context, SmsQueueService::class.java)
                    .setAction(SmsQueueService.ACTION_REFRESH)
            )
        } catch (e: IllegalStateException) {
            Log.e("SmsSender", "Could not refresh saved SMS queue state", e)
        }
    }

    val entries = queueState.entries
    val total = entries.size
    val submitted = entries.count { it.status == SmsStatus.SENT || it.status == SmsStatus.DELIVERED }
    val failed = entries.count { it.status == SmsStatus.FAILED }
    val remaining = entries.count { it.status == SmsStatus.PENDING || it.status == SmsStatus.SENDING }
    val processed = submitted + failed
    val progress = if (total == 0) 0f else processed.toFloat() / total
    val currentQueue = queueState.currentQueueIndex?.plus(1)
        ?: entries.firstOrNull { it.status == SmsStatus.PENDING }?.index
            ?.div(queueState.queueSize)?.plus(1)

    if (confirmRetry) {
        AlertDialog(
            onDismissRequest = { confirmRetry = false },
            title = { Text("Retry failed SMS?") },
            text = {
                Text(
                    "Retry $failed failed row(s)? Submission status may be uncertain for interrupted sends, " +
                        "so retrying can send a duplicate. Confirm that each recipient has consented."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRetry = false
                    startService(SmsQueueService.ACTION_RETRY_FAILED)
                }) { Text("Retry failed") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRetry = false }) { Text("Cancel") }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 16.dp)
    ) {
        Text(
            text = "Excel SMS Sender",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(top = 16.dp, bottom = 12.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = {
                    filePickerLauncher.launch(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                    )
                },
                enabled = !isLoading && queueState.runStatus !in setOf(
                    QueueRunStatus.RUNNING,
                    QueueRunStatus.PAUSED
                ),
                modifier = Modifier.weight(1f)
            ) {
                Text("Select Excel")
            }
            OutlinedTextField(
                value = queueSizeInput,
                onValueChange = { value ->
                    if (value.all(Char::isDigit)) {
                        queueSizeInput = value
                        val newSize = value.toIntOrNull()
                        if (newSize != null && newSize > 0 && queueState.entries.all {
                                it.status == SmsStatus.PENDING
                            } && queueState.runStatus == QueueRunStatus.READY
                        ) {
                            queueState = queueState.copy(queueSize = newSize)
                            SmsQueueStore.save(context, queueState)
                        }
                    }
                },
                label = { Text("Per queue") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
        }
        Text(selectedFileName, style = MaterialTheme.typography.bodyMedium)
        if (isLoading) {
            Spacer(Modifier.height(6.dp))
            CircularProgressIndicator()
        }
        if (errorMessage.isNotBlank()) {
            Text(errorMessage, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(8.dp))
        Text("Overall progress: $processed/$total (${(progress * 100).toInt()}%)")
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        Text("Queues: ${queueState.totalQueues}  •  Current: ${currentQueue ?: "—"}")
        Text("Submitted: $submitted  •  Failed: $failed  •  Remaining: $remaining")
        Row(modifier = Modifier.fillMaxWidth()) {
            Checkbox(
                checked = recipientsConsented,
                onCheckedChange = { recipientsConsented = it },
                enabled = queueState.runStatus == QueueRunStatus.READY
            )
            Text(
                "I confirm these recipients are authorized to receive these SMS messages.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 12.dp)
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text(
                    "Queues",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
            items((0 until queueState.totalQueues).toList(), key = { "queue-$it" }) { queueIndex ->
                QueueCard(queueState, queueIndex)
            }
            item {
                Text(
                    "Messages",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
            items(entries, key = { "message-${it.index}" }) { entry ->
                MessageCard(entry)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            when (queueState.runStatus) {
                QueueRunStatus.READY -> Button(
                    onClick = {
                        if (ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.SEND_SMS
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            val hasNotificationPermission =
                                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                                    ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.POST_NOTIFICATIONS
                                    ) == PackageManager.PERMISSION_GRANTED
                            if (hasNotificationPermission) {
                                startService(SmsQueueService.ACTION_START, includeQueue = true)
                            } else {
                                notificationPermissionLauncher.launch(
                                    Manifest.permission.POST_NOTIFICATIONS
                                )
                            }
                        } else {
                            smsPermissionLauncher.launch(Manifest.permission.SEND_SMS)
                        }
                    },
                    enabled = recipientsConsented && total > 0 &&
                        entries.all { it.status == SmsStatus.PENDING },
                    modifier = Modifier.weight(1f)
                ) { Text("Start Sending") }

                QueueRunStatus.RUNNING -> {
                    OutlinedButton(
                        onClick = { startService(SmsQueueService.ACTION_PAUSE) },
                        modifier = Modifier.weight(1f)
                    ) { Text("Pause") }
                    Button(
                        onClick = { startService(SmsQueueService.ACTION_STOP) },
                        modifier = Modifier.weight(1f)
                    ) { Text("Stop") }
                }

                QueueRunStatus.PAUSED -> {
                    Button(
                        onClick = { startService(SmsQueueService.ACTION_RESUME) },
                        modifier = Modifier.weight(1f)
                    ) { Text("Resume") }
                    OutlinedButton(
                        onClick = { startService(SmsQueueService.ACTION_STOP) },
                        modifier = Modifier.weight(1f)
                    ) { Text("Stop") }
                }

                QueueRunStatus.STOPPED -> {
                    if (remaining > 0) {
                        Button(
                            onClick = { startService(SmsQueueService.ACTION_RESUME) },
                            modifier = Modifier.weight(1f)
                        ) { Text("Resume") }
                    }
                    if (failed > 0) {
                        OutlinedButton(
                            onClick = { confirmRetry = true },
                            modifier = Modifier.weight(1f)
                        ) { Text("Retry failed") }
                    }
                }

                QueueRunStatus.COMPLETED -> if (failed > 0) {
                    OutlinedButton(
                        onClick = { confirmRetry = true },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Retry failed ($failed)") }
                } else {
                    Text("All SMS submissions processed")
                }
            }
        }
    }
}

@Composable
private fun QueueCard(state: SmsQueueState, queueIndex: Int) {
    val start = queueIndex * state.queueSize
    val queueEntries = state.entries.drop(start).take(state.queueSize)
    val completed = queueEntries.count {
        it.status in setOf(SmsStatus.SENT, SmsStatus.FAILED, SmsStatus.DELIVERED)
    }
    val failed = queueEntries.count { it.status == SmsStatus.FAILED }
    val remaining = queueEntries.size - completed
    val isActive = queueEntries.any { it.status == SmsStatus.SENDING } ||
        state.currentQueueIndex == queueIndex ||
        (state.runStatus == QueueRunStatus.RUNNING &&
            queueEntries.any { it.status == SmsStatus.PENDING } &&
            state.entries.take(start).none { it.status == SmsStatus.PENDING })
    val statusText = when {
        queueEntries.isEmpty() -> "Waiting"
        completed == queueEntries.size && failed > 0 -> "Completed with Errors ❌"
        completed == queueEntries.size -> "Completed ✅"
        isActive -> "In Progress 🔵"
        else -> "Waiting ⏳"
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Queue ${queueIndex + 1} — ${queueEntries.size} messages", style = MaterialTheme.typography.titleMedium)
            Text("Completed: $completed  •  Failed: $failed  •  Remaining: $remaining")
            Text(statusText)
        }
    }
}

@Composable
private fun MessageCard(entry: SmsQueueEntry) {
    val (label, color) = when (entry.status) {
        SmsStatus.PENDING -> "⏳ Pending" to MaterialTheme.colorScheme.onSurfaceVariant
        SmsStatus.SENDING -> "🔵 Sending" to MaterialTheme.colorScheme.primary
        SmsStatus.SENT -> "✅ Sent" to MaterialTheme.colorScheme.primary
        SmsStatus.FAILED -> "❌ Failed" to MaterialTheme.colorScheme.error
        SmsStatus.DELIVERED -> "🟢 Delivered" to MaterialTheme.colorScheme.primary
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(entry.message.phoneNumber, style = MaterialTheme.typography.titleMedium)
            Text(entry.message.message)
            Text(label, color = color)
            entry.failureReason?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            entry.deliveryNote?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            if (entry.status == SmsStatus.SENT && entry.deliveryNote == null) {
                Text("Delivery report may not be provided by the network", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun messagesError(error: Throwable): String {
    var cause = error
    while (cause.cause != null) cause = cause.cause!!
    return cause.message ?: "Unable to read Excel file"
}

private fun normalizePhoneNumber(raw: String): String {
    var number = raw.trim()
    // Excel often stores phone numbers as numeric cells, which drops the leading
    // zero and may render as "3193656294.0" or "3.193656294E9".
    number.toDoubleOrNull()?.let { value ->
        if (value % 1.0 == 0.0 && value < 1e15) {
            number = value.toLong().toString()
        }
    }
    // Keep only digits, preserving a leading '+'.
    val hasPlus = number.startsWith("+")
    number = number.filter(Char::isDigit)
    if (hasPlus) number = "+$number"
    // Pakistani mobile numbers are 11 digits starting with 0. Restore the zero
    // that Excel stripped from numeric cells ("3193656294" -> "03193656294").
    // Without it the destination is not routable and the radio rejects the send.
    if (!number.startsWith("+")) {
        if (number.length == 10 && number.startsWith("3")) {
            number = "0$number"
        } else if (number.length == 12 && number.startsWith("92")) {
            number = "+$number"
        }
    }
    return number
}

private fun readExcelFile(context: Context, uri: Uri): List<SmsMessage> {
    val result = mutableListOf<SmsMessage>()
    context.contentResolver.openInputStream(uri).use { inputStream ->
        requireNotNull(inputStream) { "Could not open the selected file" }
        val workbook = Excel.decodeBytes(inputStream.readBytes())
        val sheet = workbook.tables.values.firstOrNull()
            ?: throw IllegalStateException("Excel sheet not found")
        if (sheet.maxRows <= 1) return emptyList()

        val rows = sheet.selectRangeValuesWithString("A2:B${sheet.maxRows}")
        for (row in rows) {
            val phone = normalizePhoneNumber(row.getOrNull(0)?.toString().orEmpty())
            val message = row.getOrNull(1)?.toString()?.trim().orEmpty()
            if (phone.isNotBlank() || message.isNotBlank()) {
                result.add(SmsMessage(phoneNumber = phone, message = message))
            }
        }
    }
    return result
}
