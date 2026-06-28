package com.v2raytester.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EngineLogicTest {
    private val engine = TestEngine(xrayPath = "", workDir = File("."))

    @Test fun median_latency() {
        assertEquals(20, engine.median(listOf(20)))
        assertEquals(20, engine.median(listOf(30, 10, 20)))        // odd -> middle
        assertEquals(25, engine.median(listOf(10, 40, 20, 30)))    // even -> mean of middle two
        assertEquals(15, engine.median(listOf(10, 20)))
    }

    @Test fun reachable_code_mapping() {
        // usable
        for (c in listOf(200, 201, 204, 301, 302, 308, 401, 405)) {
            assertTrue("$c should be reachable", engine.reachable(c))
        }
        // not usable: censored / geo-blocked / errors
        for (c in listOf(0, 400, 403, 429, 451, 500, 502)) {
            assertFalse("$c should be unreachable", engine.reachable(c))
        }
    }
}
