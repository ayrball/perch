package com.perch.cam.camera

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraEngineStateTest {

    @Test
    fun testDefaultStateValues() {
        val state = CameraEngineState()
        assertFalse(state.isInitialized)
        assertFalse(state.isRecording)
        assertFalse(state.isStreaming)
        assertTrue(state.isMotionDetectionActive)
        assertTrue(state.isFaceDetectionActive)
        assertEquals(0, state.activeFaceCount)
        assertFalse(state.motionDetected)
        assertEquals(0L, state.currentRecordingSeconds)
        assertFalse(state.isFlashlightOn)
    }

    @Test
    fun testAtomicStateTransitions() {
        val stateFlow = MutableStateFlow(CameraEngineState())

        stateFlow.update { it.copy(isInitialized = true) }
        assertTrue(stateFlow.value.isInitialized)

        stateFlow.update { it.copy(isRecording = true, currentRecordingSeconds = 120L) }
        assertTrue(stateFlow.value.isRecording)
        assertEquals(120L, stateFlow.value.currentRecordingSeconds)

        // Simulate analysis thread updating motion without stomping isRecording
        stateFlow.update { it.copy(motionDetected = true, activeFaceCount = 2) }
        assertTrue("Recording state preserved during motion update", stateFlow.value.isRecording)
        assertTrue("Motion state correctly updated", stateFlow.value.motionDetected)
        assertEquals(2, stateFlow.value.activeFaceCount)

        // Reset on stop
        stateFlow.update { it.copy(isRecording = false, currentRecordingSeconds = 0L) }
        assertFalse(stateFlow.value.isRecording)
        assertEquals(0L, stateFlow.value.currentRecordingSeconds)
    }
}
