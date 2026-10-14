package com.perch.cam.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniJsonTest {

    @Test
    fun stringifiesNestedStructures() {
        val json = MiniJson.stringify(
            linkedMapOf(
                "a" to 1,
                "b" to listOf(true, null, "x"),
                "c" to mapOf("d" to 2.5)
            )
        )
        assertEquals("""{"a":1,"b":[true,null,"x"],"c":{"d":2.5}}""", json)
    }

    @Test
    fun escapesStrings() {
        assertEquals(""""a\"b\\c\nd\u0001"""", MiniJson.stringify("a\"b\\c\nd\u0001"))
    }

    @Test
    fun floatsKeepTheirShortDecimalForm() {
        assertEquals("33.4", MiniJson.stringify(33.4f))
    }

    @Test
    fun nonFiniteNumbersBecomeNull() {
        assertEquals("null", MiniJson.stringify(Double.NaN))
        assertEquals("null", MiniJson.stringify(Float.POSITIVE_INFINITY))
    }

    @Test
    fun parsesObjectsWithAllValueTypes() {
        val parsed = MiniJson.parseObject(""" { "on": true, "seconds": 10, "ratio": 1.5, "q": "720p", "n": null, "l": [1,2] } """)!!
        assertEquals(true, parsed["on"])
        assertEquals(10L, parsed["seconds"])
        assertEquals(1.5, parsed["ratio"])
        assertEquals("720p", parsed["q"])
        assertNull(parsed["n"])
        assertTrue(parsed.containsKey("n"))
        assertEquals(listOf(1L, 2L), parsed["l"])
    }

    @Test
    fun parsesEscapesAndUnicode() {
        val parsed = MiniJson.parseObject("""{"s":"a\"b\\nA"}""")!!
        assertEquals("a\"b\\nA", parsed["s"])
    }

    @Test
    fun roundTrips() {
        val original = mapOf("x" to "line1\nline2", "y" to listOf(1L, 2L))
        assertEquals(original, MiniJson.parseObject(MiniJson.stringify(original)))
    }

    @Test
    fun rejectsMalformedInput() {
        listOf("", "[]", "\"str\"", "{", "{\"a\":}", "{\"a\":1,}", "{a:1}", "{\"a\":1} trailing", "{\"a\":tru}")
            .forEach { assertNull("should reject: $it", MiniJson.parseObject(it)) }
    }

    @Test
    fun rejectsExcessiveNesting() {
        val deep = "{\"a\":".repeat(20) + "1" + "}".repeat(20)
        assertNull(MiniJson.parseObject(deep))
    }
}
