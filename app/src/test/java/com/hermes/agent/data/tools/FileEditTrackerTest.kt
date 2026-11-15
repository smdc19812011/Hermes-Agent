package com.hermes.agent.data.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileEditTrackerTest {
    @Test
    fun tracksWhatWasReadPerConversationAndDetectsDrift() {
        val tracker = FileEditTracker()
        assertFalse(tracker.hasRead("c1", "a.py"))

        tracker.record("c1", "a.py", "hello")
        assertTrue(tracker.hasRead("c1", "a.py"))
        assertTrue(tracker.matches("c1", "a.py", "hello"))
        assertFalse(tracker.matches("c1", "a.py", "hello!")) // changed on disk since

        // A different conversation hasn't seen it, and a re-record after a successful edit moves the baseline.
        assertFalse(tracker.hasRead("c2", "a.py"))
        tracker.record("c1", "a.py", "hello!")
        assertTrue(tracker.matches("c1", "a.py", "hello!"))
    }
}
