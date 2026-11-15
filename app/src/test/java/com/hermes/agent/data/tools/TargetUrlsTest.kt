package com.hermes.agent.data.tools

import org.junit.Assert.*
import org.junit.Test

class TargetUrlsTest {
    @Test
    fun findsASchemeUrlAndTrimsTrailingPunctuation() {
        assertEquals(listOf("https://example.com/path"), TargetUrls.extractTargets("check https://example.com/path."))
        assertEquals(listOf("https://example.com/path"), TargetUrls.extractTargets("see (https://example.com/path)"))
    }

    @Test
    fun findsABareDomainWithAPathButNotADomainAlone() {
        assertEquals(
            listOf("hirex.inputs2insights.com/admin/index.php"),
            TargetUrls.extractTargets("test the security of hirex.inputs2insights.com/admin/index.php")
        )
        assertTrue(TargetUrls.extractTargets("install Node.js and use google.com").isEmpty())
    }

    @Test
    fun findsABareIpWithAPath() {
        assertEquals(listOf("192.168.1.1/admin"), TargetUrls.extractTargets("check 192.168.1.1/admin please"))
    }

    @Test
    fun doesNotDoubleCountTheHostInsideASchemeUrl() {
        assertEquals(listOf("https://hirex.example.com/admin/index.php"), TargetUrls.extractTargets("https://hirex.example.com/admin/index.php"))
    }

    @Test
    fun ignoresVersionNumbersAndAbbreviations() {
        assertTrue(TargetUrls.extractTargets("upgraded to 1.9.1, e.g. see changelog").isEmpty())
    }

    @Test
    fun targetNoteNamesTheExactAddressAndIsNullWithoutOne() {
        val note = TargetUrls.targetNote("test hirex.inputs2insights.com/admin/index.php")!!
        assertTrue(note.contains("hirex.inputs2insights.com/admin/index.php"))
        assertTrue(note.contains("this exact address"))
        assertNull(TargetUrls.targetNote("how are you?"))
    }

    @Test
    fun targetNotePluralizesForSeveralAddresses() {
        val note = TargetUrls.targetNote("compare https://a.com/x and https://b.com/y")!!
        assertTrue(note.contains("these exact addresses"))
        assertTrue(note.contains("https://a.com/x"))
        assertTrue(note.contains("https://b.com/y"))
    }
}
