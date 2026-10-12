package com.example.excelsmssender

import org.junit.Assert.assertEquals
import org.junit.Test

class SmsQueueStateTest {
    @Test
    fun defaultQueueSizeGroupsHundredMessagesIntoTenQueues() {
        val state = SmsQueueState.create((1..100).map(::message))

        assertEquals(10, state.totalQueues)
        assertEquals((0..9).map { 10 }, queueSizes(state))
    }

    @Test
    fun finalQueueContainsOnlyRemainingMessages() {
        val state = SmsQueueState.create((1..25).map(::message))

        assertEquals(3, state.totalQueues)
        assertEquals(listOf(10, 10, 5), queueSizes(state))
        assertEquals((0..24).toList(), state.entries.map { it.index })
    }

    @Test
    fun queueSizeCanBeConfigured() {
        val state = SmsQueueState.create((1..11).map(::message), queueSize = 4)

        assertEquals(3, state.totalQueues)
        assertEquals(listOf(4, 4, 3), queueSizes(state))
    }

    private fun message(number: Int) = SmsMessage(
        phoneNumber = "+1555000${number.toString().padStart(4, '0')}",
        message = "Test $number"
    )

    private fun queueSizes(state: SmsQueueState): List<Int> =
        (0 until state.totalQueues).map { queueIndex ->
            state.entries.drop(queueIndex * state.queueSize).take(state.queueSize).size
        }
}
