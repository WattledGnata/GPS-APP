// @IgnoreFormatCheck
package com.blazepush.feature.test.diagnostic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * spec『上传元数据随包上送』：工单号归一化 + 字段透传（framework 取值由真机验证）。
 */
class DiagnosticMetadataCollectorTest {

    @Test fun build_blankTicket_normalizedToNull() {
        val m = DiagnosticMetadataCollector.build("dev", "aid", "1.0", "1", 5L, "   ")
        assertNull(m.ticket)
    }

    @Test fun build_nullTicket_null() {
        assertNull(DiagnosticMetadataCollector.build("dev", "aid", "1.0", "1", 5L, null).ticket)
    }

    @Test fun build_ticketTrimmedAndKept() {
        val m = DiagnosticMetadataCollector.build("dev", "aid", "1.0", "1", 5L, "  BUG-1 ")
        assertEquals("BUG-1", m.ticket)
    }

    @Test fun build_carriesAllFields() {
        val m = DiagnosticMetadataCollector.build("vivo V2405A", "aid", "1.0.1", "2", 9L, "T")
        assertEquals("vivo V2405A", m.deviceModel)
        assertEquals("aid", m.androidId)
        assertEquals("1.0.1", m.versionName)
        assertEquals("2", m.versionCode)
        assertEquals(9L, m.capturedAtMs)
        assertEquals("T", m.ticket)
    }
}
