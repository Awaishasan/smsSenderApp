package com.example.excelsmssender

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class SmsStatus {
    PENDING,
    SENDING,
    SENT,
    FAILED,
    DELIVERED
}

enum class QueueRunStatus {
    READY,
    RUNNING,
    PAUSED,
    STOPPED,
    COMPLETED
}

data class SmsQueueEntry(
    val index: Int,
    val message: SmsMessage,
    val status: SmsStatus = SmsStatus.PENDING,
    val failureReason: String? = null,
    val deliveryNote: String? = null
)

data class SmsQueueState(
    val sessionId: String = UUID.randomUUID().toString(),
    val queueSize: Int = DEFAULT_QUEUE_SIZE,
    val entries: List<SmsQueueEntry>,
    val runStatus: QueueRunStatus = QueueRunStatus.READY,
    val currentQueueIndex: Int? = null
) {
    val totalQueues: Int
        get() = queueCount(entries.size, queueSize)

    companion object {
        const val DEFAULT_QUEUE_SIZE = 10

        fun create(messages: List<SmsMessage>, queueSize: Int = DEFAULT_QUEUE_SIZE) =
            SmsQueueState(
                queueSize = queueSize.coerceAtLeast(1),
                entries = messages.mapIndexed { index, message ->
                    SmsQueueEntry(index = index, message = message)
                }
            )

        fun queueCount(messageCount: Int, queueSize: Int): Int =
            if (messageCount == 0) 0 else (messageCount + queueSize - 1) / queueSize
    }
}

object SmsQueueStore {
    private const val PREFERENCES = "sms_queue"
    private const val STATE_KEY = "state"

    fun save(context: android.content.Context, state: SmsQueueState) {
        context.getSharedPreferences(PREFERENCES, android.content.Context.MODE_PRIVATE)
            .edit()
            .putString(STATE_KEY, state.toJson().toString())
            .apply()
    }

    fun load(context: android.content.Context, recoverInterrupted: Boolean = false): SmsQueueState? {
        val saved = context.getSharedPreferences(PREFERENCES, android.content.Context.MODE_PRIVATE)
            .getString(STATE_KEY, null) ?: return null

        return try {
            fromJson(JSONObject(saved), recoverInterrupted)
        } catch (e: Exception) {
            android.util.Log.e("SmsSender", "Could not restore saved queue state", e)
            null
        }
    }

    fun toServiceJson(state: SmsQueueState): JSONObject = state.toJson()

    fun fromServiceJson(json: JSONObject): SmsQueueState = fromJson(json)

    private fun SmsQueueState.toJson(): JSONObject = JSONObject().apply {
        put("sessionId", sessionId)
        put("queueSize", queueSize)
        put("runStatus", runStatus.name)
        put("currentQueueIndex", currentQueueIndex ?: JSONObject.NULL)
        put("entries", JSONArray().apply {
            entries.forEach { entry ->
                put(JSONObject().apply {
                    put("index", entry.index)
                    put("phoneNumber", entry.message.phoneNumber)
                    put("message", entry.message.message)
                    put("status", entry.status.name)
                    put("failureReason", entry.failureReason ?: JSONObject.NULL)
                    put("deliveryNote", entry.deliveryNote ?: JSONObject.NULL)
                })
            }
        })
    }

    private fun fromJson(json: JSONObject, recoverInterrupted: Boolean = false): SmsQueueState {
        val jsonEntries = json.getJSONArray("entries")
        val entries = List(jsonEntries.length()) { index ->
            val item = jsonEntries.getJSONObject(index)
            val savedStatus = SmsStatus.valueOf(item.getString("status"))
            val wasSending = recoverInterrupted && savedStatus == SmsStatus.SENDING
            SmsQueueEntry(
                index = item.getInt("index"),
                message = SmsMessage(
                    phoneNumber = item.getString("phoneNumber"),
                    message = item.getString("message")
                ),
                status = if (wasSending) SmsStatus.FAILED else savedStatus,
                failureReason = if (wasSending) {
                    "App stopped while submitting; delivery is unknown. Retrying may send a duplicate."
                } else {
                    item.optStringOrNull("failureReason")
                },
                deliveryNote = item.optStringOrNull("deliveryNote")
            )
        }

        val savedRunStatus = QueueRunStatus.valueOf(json.getString("runStatus"))
        val runStatus = when {
            !recoverInterrupted -> savedRunStatus
            savedRunStatus == QueueRunStatus.RUNNING -> QueueRunStatus.STOPPED
            entries.isNotEmpty() && entries.all { it.status in setOf(SmsStatus.SENT, SmsStatus.FAILED, SmsStatus.DELIVERED) } ->
                QueueRunStatus.COMPLETED
            else -> savedRunStatus
        }

        return SmsQueueState(
            sessionId = json.getString("sessionId"),
            queueSize = json.getInt("queueSize").coerceAtLeast(1),
            entries = entries,
            runStatus = runStatus,
            currentQueueIndex = if (recoverInterrupted || json.isNull("currentQueueIndex")) {
                null
            } else {
                json.getInt("currentQueueIndex")
            }
        )
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf(String::isNotEmpty)
}
