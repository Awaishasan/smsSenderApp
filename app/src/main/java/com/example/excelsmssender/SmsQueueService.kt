package com.example.excelsmssender

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telephony.PhoneNumberUtils
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject

class SmsQueueService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var queue: SmsQueueState? = null
    private var activeMessageIndex: Int? = null
    private var isForeground = false
    private val multipartTrackers = mutableMapOf<Int, MultipartTracker>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (queue == null && intent?.action != ACTION_START) {
            queue = SmsQueueStore.load(this, recoverInterrupted = intent?.action == ACTION_REFRESH)
            if (intent?.action == ACTION_SMS_CALLBACK) {
                activeMessageIndex = queue?.entries?.firstOrNull { it.status == SmsStatus.SENDING }?.index
            }
        }

        if (intent?.action in setOf(ACTION_START, ACTION_RESUME, ACTION_RETRY_FAILED) ||
            queue?.runStatus in setOf(QueueRunStatus.RUNNING, QueueRunStatus.PAUSED)
        ) {
            startForeground(NOTIFICATION_ID, buildNotification())
            isForeground = true
        }

        when (intent?.action) {
            ACTION_START -> startQueue(intent.getStringExtra(EXTRA_QUEUE_JSON))
            ACTION_PAUSE -> pauseQueue()
            ACTION_RESUME -> resumeQueue()
            ACTION_STOP -> stopQueue()
            ACTION_RETRY_FAILED -> retryFailed()
            ACTION_SMS_CALLBACK -> handleSmsCallback(intent)
            ACTION_REFRESH -> {
                publishState()
                if (queue?.runStatus !in setOf(QueueRunStatus.RUNNING, QueueRunStatus.PAUSED)) {
                    stopService()
                }
            }
            else -> Log.w(TAG, "Ignoring unknown service action: ${intent?.action}")
        }
        return START_NOT_STICKY
    }

    private fun startQueue(json: String?) {
        if (queue?.runStatus in setOf(QueueRunStatus.RUNNING, QueueRunStatus.PAUSED)) {
            Log.w(TAG, "Ignoring duplicate start request for active queue")
            return
        }
        if (json == null) {
            Log.e(TAG, "Queue start request did not contain queue data")
            stopService()
            return
        }

        queue = try {
            SmsQueueStore.fromServiceJson(JSONObject(json))
        } catch (e: Exception) {
            Log.e(TAG, "Could not decode SMS queue", e)
            stopService()
            return
        }
        queue = queue?.copy(runStatus = QueueRunStatus.RUNNING)
        Log.i(TAG, "Starting SMS session ${queue?.sessionId} with ${queue?.entries?.size} messages")
        publishState()
        processNext()
    }

    private fun pauseQueue() {
        val current = queue ?: return
        if (current.runStatus != QueueRunStatus.RUNNING) return
        queue = current.copy(runStatus = QueueRunStatus.PAUSED)
        Log.i(TAG, "Queue paused; waiting for current SMS callback if one is in flight")
        publishState()
    }

    private fun resumeQueue() {
        val current = queue ?: return
        if (current.runStatus !in setOf(QueueRunStatus.PAUSED, QueueRunStatus.STOPPED)) return
        queue = current.copy(runStatus = QueueRunStatus.RUNNING)
        Log.i(TAG, "Queue resumed")
        publishState()
        processNext()
    }

    private fun stopQueue() {
        val current = queue ?: return
        if (current.runStatus !in setOf(QueueRunStatus.RUNNING, QueueRunStatus.PAUSED)) return
        val isComplete = activeMessageIndex == null && current.entries.none {
            it.status == SmsStatus.PENDING || it.status == SmsStatus.SENDING
        }
        queue = current.copy(
            runStatus = if (isComplete) QueueRunStatus.COMPLETED else QueueRunStatus.STOPPED,
            currentQueueIndex = if (isComplete) null else current.currentQueueIndex
        )
        Log.i(TAG, "Queue stopped; no further messages will be submitted")
        publishState()
        if (activeMessageIndex == null) stopService()
    }

    private fun retryFailed() {
        val current = queue ?: return
        if (current.runStatus in setOf(QueueRunStatus.RUNNING, QueueRunStatus.PAUSED)) return
        val retryCount = current.entries.count { it.status == SmsStatus.FAILED }
        if (retryCount == 0) return
        queue = current.copy(
            entries = current.entries.map { entry ->
                if (entry.status == SmsStatus.FAILED) {
                    entry.copy(status = SmsStatus.PENDING, failureReason = null, deliveryNote = null)
                } else {
                    entry
                }
            },
            runStatus = QueueRunStatus.RUNNING
        )
        Log.i(TAG, "Explicitly retrying $retryCount failed messages")
        publishState()
        processNext()
    }

    private fun processNext() {
        val inMemory = queue ?: return
        queue = SmsQueueStore.load(this)?.takeIf { it.sessionId == inMemory.sessionId } ?: inMemory
        val current = queue ?: return
        if (current.runStatus != QueueRunStatus.RUNNING || activeMessageIndex != null) return

        val entry = current.entries.firstOrNull { it.status == SmsStatus.PENDING }
        if (entry == null) {
            queue = current.copy(
                runStatus = if (current.entries.any { it.status == SmsStatus.SENDING }) {
                    QueueRunStatus.STOPPED
                } else {
                    QueueRunStatus.COMPLETED
                },
                currentQueueIndex = null
            )
            Log.i(TAG, "SMS queue processing complete")
            publishState()
            if (queue?.runStatus == QueueRunStatus.COMPLETED) stopService()
            return
        }

        val queueNumber = entry.index / current.queueSize
        queue = current.copy(
            entries = current.entries.replaceEntry(entry.index) {
                it.copy(status = SmsStatus.SENDING, failureReason = null)
            },
            currentQueueIndex = queueNumber
        )
        activeMessageIndex = entry.index
        publishState()
        submitSms(entry, current.sessionId)
    }

    private fun submitSms(entry: SmsQueueEntry, sessionId: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            failCurrent(entry.index, "SMS permission is no longer granted")
            queue = queue?.copy(runStatus = QueueRunStatus.STOPPED)
            publishState()
            stopService()
            return
        }

        val phone = entry.message.phoneNumber.trim()
        if (!PhoneNumberUtils.isGlobalPhoneNumber(phone)) {
            failCurrent(entry.index, "Invalid phone number")
            handler.post(::processNext)
            return
        }
        if (entry.message.message.isBlank()) {
            failCurrent(entry.index, "Message is empty")
            handler.post(::processNext)
            return
        }

        val telephonyManager = getSystemService(TelephonyManager::class.java)
        if (telephonyManager == null || !telephonyManager.isSmsCapable) {
            failCurrent(entry.index, "This device cannot send SMS")
            handler.post(::processNext)
            return
        }

        val sentIntent = callbackIntent(sessionId, entry.index, CALLBACK_SENT)
        val deliveryIntent = callbackIntent(sessionId, entry.index, CALLBACK_DELIVERED)
        try {
            val smsManager = getSystemService(SmsManager::class.java)
            if (smsManager == null) {
                failCurrent(entry.index, "SMS service is unavailable")
                handler.post(::processNext)
                return
            }
            val parts = smsManager.divideMessage(entry.message.message)
            if (parts.size <= 1) {
                smsManager.sendTextMessage(phone, null, entry.message.message, sentIntent, deliveryIntent)
                Log.i(TAG, "Submitted SMS request for row ${entry.index + 1} to $phone; awaiting platform callback")
            } else {
                // A single SMS segment holds 160 GSM chars (70 for Unicode/Urdu). Longer
                // messages are rejected by the radio with a generic failure even when the
                // SIM has balance, so split them and send as multipart instead.
                val sentIntents = ArrayList<PendingIntent>(parts.size)
                val deliveryIntents = ArrayList<PendingIntent>(parts.size)
                for (partIndex in parts.indices) {
                    sentIntents.add(callbackIntent(sessionId, entry.index, CALLBACK_SENT, partIndex))
                    deliveryIntents.add(callbackIntent(sessionId, entry.index, CALLBACK_DELIVERED, partIndex))
                }
                multipartTrackers[entry.index] = MultipartTracker(parts.size)
                smsManager.sendMultipartTextMessage(phone, null, parts, sentIntents, deliveryIntents)
                Log.i(TAG, "Submitted ${parts.size}-part SMS for row ${entry.index + 1} to $phone; awaiting platform callbacks")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SMS permission or platform policy prevented row ${entry.index + 1}", e)
            failCurrent(entry.index, "SMS permission or platform policy denied submission")
            queue = queue?.copy(runStatus = QueueRunStatus.STOPPED)
            publishState()
            stopService()
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Invalid SMS data for row ${entry.index + 1}", e)
            failCurrent(entry.index, e.message ?: "Invalid SMS data")
            handler.post(::processNext)
        } catch (e: UnsupportedOperationException) {
            Log.e(TAG, "SMS is not supported for row ${entry.index + 1}", e)
            failCurrent(entry.index, e.message ?: "SMS is not supported")
            handler.post(::processNext)
        } catch (e: IllegalStateException) {
            Log.e(TAG, "SMS service is unavailable for row ${entry.index + 1}", e)
            failCurrent(entry.index, e.message ?: "SMS service is unavailable")
            handler.post(::processNext)
        }
    }

    private fun callbackIntent(sessionId: String, index: Int, callbackType: String, partIndex: Int = 0): PendingIntent {
        val action = if (callbackType == CALLBACK_SENT) ACTION_SMS_SENT else ACTION_SMS_DELIVERED
        val intent = Intent(this, SmsCallbackReceiver::class.java)
            .setAction(action)
            .setData(android.net.Uri.parse("sms-sender://$sessionId/$callbackType/$index/$partIndex"))
            .putExtra(EXTRA_SESSION_ID, sessionId)
            .putExtra(EXTRA_MESSAGE_INDEX, index)
            .putExtra(EXTRA_CALLBACK_TYPE, callbackType)
            .putExtra(EXTRA_PART_INDEX, partIndex)
        return PendingIntent.getBroadcast(
            this,
            index * 1000 + partIndex,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun handleSmsCallback(intent: Intent) {
        val current = queue ?: return
        val callbackSession = intent.getStringExtra(EXTRA_SESSION_ID)
        if (callbackSession != current.sessionId) {
            Log.w(TAG, "Ignoring SMS callback for an old session")
            return
        }

        val index = intent.getIntExtra(EXTRA_MESSAGE_INDEX, -1)
        val callbackType = intent.getStringExtra(EXTRA_CALLBACK_TYPE)
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE)
        if (index !in current.entries.indices) {
            Log.e(TAG, "Ignoring callback with invalid message index $index")
            return
        }

        val partIndex = intent.getIntExtra(EXTRA_PART_INDEX, 0)
        val tracker = multipartTrackers[index]

        if (callbackType == CALLBACK_DELIVERED) {
            if (tracker != null) {
                handleMultipartDeliveryCallback(index, partIndex, resultCode, tracker)
            } else {
                handleDeliveryCallback(index, resultCode)
            }
            if (queue?.runStatus !in setOf(QueueRunStatus.RUNNING, QueueRunStatus.PAUSED)) {
                stopService()
            }
            return
        }
        if (callbackType != CALLBACK_SENT || activeMessageIndex != index) {
            Log.w(TAG, "Ignoring duplicate or unexpected sent callback for row ${index + 1}")
            return
        }

        if (tracker != null) {
            handleMultipartSentCallback(index, partIndex, resultCode, tracker)
            return
        }

        activeMessageIndex = null
        if (resultCode == android.app.Activity.RESULT_OK) {
            updateEntry(index) { it.copy(status = SmsStatus.SENT, failureReason = null) }
            Log.i(TAG, "Android accepted SMS submission for row ${index + 1}")
        } else {
            val reason = sentFailureReason(resultCode)
            updateEntry(index) { it.copy(status = SmsStatus.FAILED, failureReason = reason) }
            Log.e(TAG, "SMS submission failed for row ${index + 1}: $reason (result=$resultCode)")
        }

        publishState()
        if (queue?.runStatus == QueueRunStatus.STOPPED) {
            val latest = queue
            if (latest != null && latest.entries.none {
                    it.status == SmsStatus.PENDING || it.status == SmsStatus.SENDING
                }
            ) {
                queue = latest.copy(runStatus = QueueRunStatus.COMPLETED, currentQueueIndex = null)
                publishState()
            }
            stopService()
        } else {
            handler.post(::processNext)
        }
    }

    private fun handleDeliveryCallback(index: Int, resultCode: Int) {
        val entry = queue?.entries?.getOrNull(index) ?: return
        if (entry.status !in setOf(SmsStatus.SENT, SmsStatus.DELIVERED)) {
            Log.w(TAG, "Delivery report arrived before a successful submission callback for row ${index + 1}")
            return
        }
        if (resultCode == android.app.Activity.RESULT_OK) {
            updateEntry(index) { it.copy(status = SmsStatus.DELIVERED, deliveryNote = null) }
            Log.i(TAG, "Delivery report confirmed for row ${index + 1}")
        } else {
            val reason = deliveryFailureReason(resultCode)
            updateEntry(index) { it.copy(deliveryNote = reason) }
            Log.w(TAG, "Delivery report did not confirm row ${index + 1}: $reason (result=$resultCode)")
        }
        publishState()
    }

    private fun handleMultipartSentCallback(index: Int, partIndex: Int, resultCode: Int, tracker: MultipartTracker) {
        val safePart = partIndex.coerceIn(0, tracker.totalParts - 1)
        tracker.sentOk[safePart] = resultCode == android.app.Activity.RESULT_OK
        if (resultCode != android.app.Activity.RESULT_OK && tracker.firstSentFailure == null) {
            tracker.firstSentFailure = resultCode
        }
        tracker.sentCount++
        Log.i(TAG, "Sent callback for row ${index + 1} part ${safePart + 1}/${tracker.totalParts}: result=$resultCode")
        if (!tracker.sentComplete) {
            // Wait for the remaining parts; keep activeMessageIndex so the queue does not advance early.
            return
        }

        activeMessageIndex = null
        if (tracker.firstSentFailure == null) {
            updateEntry(index) { it.copy(status = SmsStatus.SENT, failureReason = null) }
            Log.i(TAG, "Android accepted all ${tracker.totalParts} SMS parts for row ${index + 1}")
        } else {
            val reason = sentFailureReason(tracker.firstSentFailure!!)
            updateEntry(index) { it.copy(status = SmsStatus.FAILED, failureReason = reason) }
            Log.e(TAG, "SMS submission failed for row ${index + 1}: $reason (result=${tracker.firstSentFailure})")
            multipartTrackers.remove(index)
        }
        publishState()
        if (tracker.firstSentFailure == null && tracker.deliveryComplete) {
            finishMultipartDelivery(index, tracker)
        }

        if (queue?.runStatus == QueueRunStatus.STOPPED) {
            val latest = queue
            if (latest != null && latest.entries.none {
                    it.status == SmsStatus.PENDING || it.status == SmsStatus.SENDING
                }
            ) {
                queue = latest.copy(runStatus = QueueRunStatus.COMPLETED, currentQueueIndex = null)
                publishState()
            }
            stopService()
        } else {
            handler.post(::processNext)
        }
    }

    private fun handleMultipartDeliveryCallback(index: Int, partIndex: Int, resultCode: Int, tracker: MultipartTracker) {
        val safePart = partIndex.coerceIn(0, tracker.totalParts - 1)
        tracker.deliveryOk[safePart] = resultCode == android.app.Activity.RESULT_OK
        if (resultCode != android.app.Activity.RESULT_OK && tracker.firstDeliveryFailure == null) {
            tracker.firstDeliveryFailure = resultCode
        }
        tracker.deliveryCount++
        if (!tracker.deliveryComplete) return
        val entry = queue?.entries?.getOrNull(index)
        if (entry?.status in setOf(SmsStatus.SENT, SmsStatus.DELIVERED)) {
            finishMultipartDelivery(index, tracker)
        } else {
            multipartTrackers.remove(index)
            Log.w(TAG, "Multipart delivery reports finished without a successful submission for row ${index + 1}")
        }
    }

    private fun finishMultipartDelivery(index: Int, tracker: MultipartTracker) {
        multipartTrackers.remove(index)
        if (tracker.firstDeliveryFailure == null) {
            updateEntry(index) { it.copy(status = SmsStatus.DELIVERED, deliveryNote = null) }
            Log.i(TAG, "Delivery reports confirmed all ${tracker.totalParts} parts of row ${index + 1}")
        } else {
            val note = deliveryFailureReason(tracker.firstDeliveryFailure!!)
            updateEntry(index) { it.copy(deliveryNote = note) }
            Log.w(TAG, "Delivery reports did not confirm all parts of row ${index + 1}: $note")
        }
        publishState()
    }

    private fun failCurrent(index: Int, reason: String) {
        activeMessageIndex = null
        updateEntry(index) { it.copy(status = SmsStatus.FAILED, failureReason = reason) }
        Log.e(TAG, "SMS failed for row ${index + 1}: $reason")
        publishState()
    }

    private fun updateEntry(index: Int, transform: (SmsQueueEntry) -> SmsQueueEntry) {
        val current = queue ?: return
        queue = current.copy(entries = current.entries.replaceEntry(index, transform))
    }

    private fun publishState() {
        val current = queue ?: return
        SmsQueueStore.save(this, current)
        sendBroadcast(
            Intent(ACTION_STATE_UPDATED)
                .setPackage(packageName)
                .putExtra(EXTRA_QUEUE_JSON, SmsQueueStore.toServiceJson(current).toString())
        )
        if (isForeground) {
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun buildNotification(): Notification {
        val current = queue
        val total = current?.entries?.size ?: 0
        val completed = current?.entries?.count {
            it.status in setOf(SmsStatus.SENT, SmsStatus.FAILED, SmsStatus.DELIVERED)
        } ?: 0
        val text = when (current?.runStatus) {
            QueueRunStatus.PAUSED -> "Paused • $completed of $total processed"
            QueueRunStatus.STOPPED -> "Stopped • $completed of $total processed"
            QueueRunStatus.COMPLETED -> "Complete • $completed of $total processed"
            else -> "Sending • $completed of $total processed"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_action_email)
            .setContentTitle("Excel SMS Sender")
            .setContentText(text)
            .setOngoing(current?.runStatus in setOf(QueueRunStatus.RUNNING, QueueRunStatus.PAUSED))
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SMS queue",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun stopService() {
        if (isForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        stopSelf()
    }

    override fun onDestroy() {
        if (activeMessageIndex != null) {
            Log.w(TAG, "Service destroyed during SMS submission; saved state will mark it uncertain")
        }
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.example.excelsmssender.action.START"
        const val ACTION_PAUSE = "com.example.excelsmssender.action.PAUSE"
        const val ACTION_RESUME = "com.example.excelsmssender.action.RESUME"
        const val ACTION_STOP = "com.example.excelsmssender.action.STOP"
        const val ACTION_RETRY_FAILED = "com.example.excelsmssender.action.RETRY_FAILED"
        const val ACTION_SMS_CALLBACK = "com.example.excelsmssender.action.SMS_CALLBACK"
        const val ACTION_REFRESH = "com.example.excelsmssender.action.REFRESH"
        const val ACTION_STATE_UPDATED = "com.example.excelsmssender.action.STATE_UPDATED"
        const val ACTION_SMS_SENT = "com.example.excelsmssender.action.SMS_SENT"
        const val ACTION_SMS_DELIVERED = "com.example.excelsmssender.action.SMS_DELIVERED"
        const val CALLBACK_SENT = "sent"
        const val CALLBACK_DELIVERED = "delivered"
        const val EXTRA_QUEUE_JSON = "queue_json"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_MESSAGE_INDEX = "message_index"
        const val EXTRA_CALLBACK_TYPE = "callback_type"
        const val EXTRA_PART_INDEX = "part_index"
        const val EXTRA_RESULT_CODE = "result_code"
        private const val TAG = "SmsSender"
        private const val CHANNEL_ID = "sms_queue"
        private const val NOTIFICATION_ID = 1001
    }
}

class SmsCallbackReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val callbackType = if (intent.action == SmsQueueService.ACTION_SMS_SENT) {
            SmsQueueService.CALLBACK_SENT
        } else if (intent.action == SmsQueueService.ACTION_SMS_DELIVERED) {
            SmsQueueService.CALLBACK_DELIVERED
        } else {
            Log.w("SmsSender", "Ignoring unknown SMS callback action ${intent.action}")
            return
        }

        val callback = Intent(context, SmsQueueService::class.java)
            .setAction(SmsQueueService.ACTION_SMS_CALLBACK)
            .putExtra(SmsQueueService.EXTRA_SESSION_ID, intent.getStringExtra(SmsQueueService.EXTRA_SESSION_ID))
            .putExtra(SmsQueueService.EXTRA_MESSAGE_INDEX, intent.getIntExtra(SmsQueueService.EXTRA_MESSAGE_INDEX, -1))
            .putExtra(SmsQueueService.EXTRA_CALLBACK_TYPE, callbackType)
            .putExtra(SmsQueueService.EXTRA_RESULT_CODE, resultCode)
        try {
            context.startService(callback)
        } catch (e: IllegalStateException) {
            if (callbackType == SmsQueueService.CALLBACK_DELIVERED) {
                Log.w("SmsSender", "Queue service unavailable; applying delivery report directly", e)
                updateDeliveryReport(context, intent)
            } else {
                Log.e("SmsSender", "Could not deliver SMS result to the queue service", e)
            }
        }
    }

    private fun updateDeliveryReport(context: Context, intent: Intent) {
        val state = SmsQueueStore.load(context) ?: run {
            Log.w("SmsSender", "No saved queue found for delivery report")
            return
        }
        if (intent.getStringExtra(SmsQueueService.EXTRA_SESSION_ID) != state.sessionId) {
            Log.w("SmsSender", "Ignoring delivery report for an old SMS session")
            return
        }
        val index = intent.getIntExtra(SmsQueueService.EXTRA_MESSAGE_INDEX, -1)
        val entry = state.entries.getOrNull(index)
        if (entry == null || entry.status !in setOf(SmsStatus.SENT, SmsStatus.DELIVERED)) {
            Log.w("SmsSender", "Ignoring delivery report without a successful submission for row ${index + 1}")
            return
        }

        val resultCode = resultCode
        val updatedEntry = if (resultCode == android.app.Activity.RESULT_OK) {
            Log.i("SmsSender", "Delivery report confirmed for row ${index + 1}")
            entry.copy(status = SmsStatus.DELIVERED, deliveryNote = null)
        } else {
            val note = deliveryFailureReason(resultCode)
            Log.w("SmsSender", "Delivery report did not confirm row ${index + 1}: $note")
            entry.copy(deliveryNote = note)
        }
        val updatedState = state.copy(
            entries = state.entries.map { if (it.index == index) updatedEntry else it }
        )
        SmsQueueStore.save(context, updatedState)
        context.sendBroadcast(
            Intent(SmsQueueService.ACTION_STATE_UPDATED)
                .setPackage(context.packageName)
                .putExtra(
                    SmsQueueService.EXTRA_QUEUE_JSON,
                    SmsQueueStore.toServiceJson(updatedState).toString()
                )
        )
    }
}

private fun List<SmsQueueEntry>.replaceEntry(
    index: Int,
    transform: (SmsQueueEntry) -> SmsQueueEntry
): List<SmsQueueEntry> = map { entry -> if (entry.index == index) transform(entry) else entry }

private fun sentFailureReason(resultCode: Int): String = when (resultCode) {
    SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "Generic SMS submission failure"
    SmsManager.RESULT_ERROR_NO_SERVICE -> "No mobile network service"
    SmsManager.RESULT_ERROR_NULL_PDU -> "SMS provider returned an empty PDU"
    SmsManager.RESULT_ERROR_RADIO_OFF -> "Mobile radio is off"
    else -> "SMS submission failed (result $resultCode)"
}

private fun deliveryFailureReason(resultCode: Int): String = when (resultCode) {
    SmsManager.RESULT_ERROR_NO_SERVICE -> "Delivery report unavailable: no network service"
    SmsManager.RESULT_ERROR_RADIO_OFF -> "Delivery report unavailable: mobile radio is off"
    else -> "Delivery report unavailable or failed (result $resultCode)"
}

private class MultipartTracker(val totalParts: Int) {
    val sentOk = BooleanArray(totalParts)
    var sentCount = 0
    var firstSentFailure: Int? = null
    val deliveryOk = BooleanArray(totalParts)
    var deliveryCount = 0
    var firstDeliveryFailure: Int? = null
    val sentComplete: Boolean get() = sentCount >= totalParts
    val deliveryComplete: Boolean get() = deliveryCount >= totalParts
}
