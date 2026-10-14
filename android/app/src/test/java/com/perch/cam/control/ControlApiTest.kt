package com.perch.cam.control

import com.perch.cam.camera.FaceBox
import com.perch.cam.data.AlertEvent
import com.perch.cam.data.AlertType
import com.perch.cam.util.MiniJson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeBackend : ControlBackend {
    var flashlightSupported = true
    var flashlight = false
    var sirenSeconds: Int? = null
    var sirenStops = 0
    var flips = 0
    var currentQuality = "1080p"
    var lastEventLimit = -1

    override suspend fun status() = StatusSnapshot(
        deviceName = "Test Phone",
        timestamp = 1_700_000_000_000,
        recording = true,
        recordingSeconds = 252,
        segmentMinutes = 5,
        streaming = false,
        streamState = "IDLE",
        motion = false,
        faceCount = 1,
        faces = listOf(FaceBox(0.1f, 0.2f, 0.3f, 0.4f)),
        flashlight = flashlight,
        camera = "back",
        videoQuality = currentQuality,
        throttled = false,
        sirenActive = sirenSeconds != null,
        battery = BatteryInfo(92, true, 33.4f),
        storage = StorageInfo(100, 8_589_934_592, 5_000),
        backup = BackupInfo("connected", "me@example.com", true, 15, 2, 40, 4_200_000_000, null)
    )

    override suspend fun events(limit: Int): List<AlertEvent> {
        lastEventLimit = limit
        return listOf(AlertEvent(id = 7, type = AlertType.MOTION, title = "Motion Detected", description = "d", timestamp = 5))
    }

    override fun setFlashlight(on: Boolean?): Boolean {
        if (!flashlightSupported) return false
        flashlight = on ?: !flashlight
        return true
    }

    override fun startSiren(seconds: Int) { sirenSeconds = seconds }
    override fun stopSiren() { sirenStops++; sirenSeconds = null }
    override fun flipCamera() { flips++ }
    override fun setQuality(quality: String) { currentQuality = quality }
}

class ControlApiTest {

    private val token = "secret-token-123"
    private val backend = FakeBackend()
    private val api = ControlApi(backend) { token }

    private fun call(
        method: String,
        path: String,
        body: String = "",
        auth: String? = "Bearer $token",
        query: Map<String, String> = emptyMap()
    ): ControlApi.Response = runBlocking {
        val headers = if (auth != null) mapOf("authorization" to auth) else emptyMap()
        api.handle(ControlApi.Request(method, path, query, headers, body))
    }

    private fun json(response: ControlApi.Response) = MiniJson.parseObject(response.body)!!

    @Test
    fun rejectsMissingWrongAndMalformedCredentials() {
        assertEquals(401, call("GET", "/api/status", auth = null).status)
        assertEquals(401, call("GET", "/api/status", auth = "Bearer nope").status)
        assertEquals(401, call("GET", "/api/status", auth = token).status) // no "Bearer " prefix
        assertEquals(401, call("GET", "/api/status", auth = "Bearer ").status)
    }

    @Test
    fun refusesEverythingWhenNoTokenIsConfigured() {
        val open = ControlApi(backend) { "" }
        val response = runBlocking {
            open.handle(ControlApi.Request("GET", "/api/status", headers = mapOf("authorization" to "Bearer ")))
        }
        assertEquals(401, response.status)
    }

    @Test
    fun statusMatchesTheDocumentedShape() {
        val response = call("GET", "/api/status")
        assertEquals(200, response.status)
        val status = json(response)
        assertEquals("Test Phone", status["deviceName"])
        assertEquals(true, status["recording"])
        assertEquals(252L, status["recordingSeconds"])
        assertEquals(false, status["sirenActive"])
        @Suppress("UNCHECKED_CAST")
        val battery = status["battery"] as Map<String, Any?>
        assertEquals(92L, battery["percent"])
        assertEquals(true, battery["charging"])
        @Suppress("UNCHECKED_CAST")
        val backup = status["backup"] as Map<String, Any?>
        assertEquals("connected", backup["state"])
        assertNull(backup["quotaTotalBytes"])
        assertTrue(backup.containsKey("quotaTotalBytes"))
        @Suppress("UNCHECKED_CAST")
        val faces = status["faces"] as List<Map<String, Any?>>
        assertEquals(1, faces.size)
        assertNotNull(faces[0]["w"])
    }

    @Test
    fun eventsAreClampedAndSerialized() {
        val response = call("GET", "/api/events", query = mapOf("limit" to "99999"))
        assertEquals(200, response.status)
        assertEquals(ControlApi.MAX_EVENT_LIMIT, backend.lastEventLimit)
        assertTrue(response.body.startsWith("[{"))
        assertTrue(response.body.contains("\"type\":\"MOTION\""))

        call("GET", "/api/events", query = mapOf("limit" to "abc"))
        assertEquals(ControlApi.DEFAULT_EVENT_LIMIT, backend.lastEventLimit)
    }

    @Test
    fun flashlightSetsTogglesAndReportsUnsupportedCameras() {
        assertEquals(200, call("POST", "/api/flashlight", """{"on":true}""").status)
        assertTrue(backend.flashlight)
        assertEquals(200, call("POST", "/api/flashlight").status) // toggle
        assertFalse(backend.flashlight)

        assertEquals(400, call("POST", "/api/flashlight", """{"on":"yes"}""").status)
        assertEquals(400, call("POST", "/api/flashlight", "not json").status)

        backend.flashlightSupported = false
        assertEquals(409, call("POST", "/api/flashlight", """{"on":true}""").status)
    }

    @Test
    fun sirenUsesDefaultClampsAndStops() {
        assertEquals(200, call("POST", "/api/siren").status)
        assertEquals(ControlApi.DEFAULT_SIREN_SECONDS, backend.sirenSeconds)

        call("POST", "/api/siren", """{"seconds":100000}""")
        assertEquals(ControlApi.MAX_SIREN_SECONDS, backend.sirenSeconds)
        call("POST", "/api/siren", """{"seconds":0}""")
        assertEquals(1, backend.sirenSeconds)

        assertEquals(400, call("POST", "/api/siren", """{"seconds":"long"}""").status)

        val stopped = call("POST", "/api/siren/stop")
        assertEquals(200, stopped.status)
        assertEquals(1, backend.sirenStops)
        assertEquals(false, json(stopped)["sirenActive"])
    }

    @Test
    fun sirenStatusIsAlwaysPresentInActionResponses() {
        listOf("/api/camera/flip", "/api/siren/stop").forEach {
            assertTrue(json(call("POST", it)).containsKey("sirenActive"))
        }
    }

    @Test
    fun flipAndQualityValidateInput() {
        assertEquals(200, call("POST", "/api/camera/flip").status)
        assertEquals(1, backend.flips)

        assertEquals(200, call("POST", "/api/quality", """{"quality":"720p"}""").status)
        assertEquals("720p", backend.currentQuality)
        assertEquals(400, call("POST", "/api/quality", """{"quality":"8K"}""").status)
        assertEquals(400, call("POST", "/api/quality", "{}").status)
        assertEquals("720p", backend.currentQuality)
    }

    @Test
    fun wrongMethodAndUnknownRoutes() {
        assertEquals(405, call("POST", "/api/status").status)
        assertEquals(405, call("GET", "/api/siren").status)
        assertEquals(404, call("GET", "/api/nope").status)
        assertEquals(200, call("GET", "/api/status/").status) // trailing slash tolerated
    }
}

class AuthThrottleTest {

    @Test
    fun blocksAfterRepeatedFailuresUntilTheWindowPasses() {
        val throttle = AuthThrottle(maxFailures = 3, windowMs = 1000)
        repeat(3) { throttle.recordFailure("1.2.3.4", now = 100L * it) }

        assertTrue(throttle.isBlocked("1.2.3.4", now = 500))
        assertFalse("other addresses are unaffected", throttle.isBlocked("5.6.7.8", now = 500))
        assertFalse("window expired", throttle.isBlocked("1.2.3.4", now = 5_000))
    }

    @Test
    fun successClearsTheFailureCount() {
        val throttle = AuthThrottle(maxFailures = 2, windowMs = 1000)
        throttle.recordFailure("a", 0)
        throttle.recordSuccess("a")
        throttle.recordFailure("a", 10)
        assertFalse(throttle.isBlocked("a", 20))
    }
}
