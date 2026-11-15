package com.hermes.agent.domain.usecase

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnfinishedIntentLogicTest {
    @Test
    fun catchesTheExactPatternFromTheReportedFailure() {
        assertTrue(UnfinishedIntentLogic.looksLikeUnfinishedIntent(
            "The issue is Gradle 9.x is incompatible with Android Gradle Plugin 8.1.0. Let me upgrade the AGP version to one compatible with Gradle 9.x."
        ))
        assertTrue(UnfinishedIntentLogic.looksLikeUnfinishedIntent("Now let me check if we have Gradle and the Android SDK available to build this."))
        assertTrue(UnfinishedIntentLogic.looksLikeUnfinishedIntent("I'll set up a minimal Android project and build it."))
        assertTrue(UnfinishedIntentLogic.looksLikeUnfinishedIntent("That didn't work. I need to try a different approach."))
    }

    @Test
    fun aRealFinishedAnswerIsNotFlagged() {
        assertFalse(UnfinishedIntentLogic.looksLikeUnfinishedIntent("Done — the APK is built and signed at ~/DateTimeApp/app-release.apk."))
        assertFalse(UnfinishedIntentLogic.looksLikeUnfinishedIntent("Here's what I found: three devices are on the network, all reachable."))
    }

    @Test
    fun commonClosingsThatHappenToContainAnIntentPhraseAreExcluded() {
        assertFalse(UnfinishedIntentLogic.looksLikeUnfinishedIntent("Everything's installed. Let me know if you have questions."))
        assertFalse(UnfinishedIntentLogic.looksLikeUnfinishedIntent("I'll let you know if anything else comes up."))
    }

    @Test
    fun onlyTheEndOfTheReplyIsChecked() {
        // An intent phrase used earlier while narrating past steps shouldn't count once the reply has since concluded.
        val text = "Let me check the SDK first. " + "x".repeat(250) + " The build finished successfully."
        assertFalse(UnfinishedIntentLogic.looksLikeUnfinishedIntent(text, tailChars = 50))
    }

    @Test
    fun blankTextIsNeverFlagged() {
        assertFalse(UnfinishedIntentLogic.looksLikeUnfinishedIntent(""))
        assertFalse(UnfinishedIntentLogic.looksLikeUnfinishedIntent("   "))
    }
}
