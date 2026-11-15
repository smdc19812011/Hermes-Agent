package com.hermes.agent.domain.usecase

/**
 * Catches a specific, observed failure of weaker models: ending a reply on "let me do X" / "I'll do X" without
 * actually calling the tool for X, then stopping as if the task were finished. Pure so it's unit-tested; the
 * caller decides what to do about a match (nudge the model to actually act, bounded so it can't loop forever).
 */
object UnfinishedIntentLogic {
    private val INTENT_PHRASES = listOf(
        "let me ", "i'll ", "i will ", "let's ", "going to ", "i'm going to ", "next i'll ", "i need to ", "i should "
    )
    // Common closings that happen to contain an intent phrase but aren't actually promising more work.
    private val EXCLUSIONS = listOf("let me know", "i'll let you know", "i will let you know", "let me know if")

    /** Looks only at the end of [text] (one round's reply), so an intent phrase earlier in a longer explanation doesn't count. */
    fun looksLikeUnfinishedIntent(text: String, tailChars: Int = 220): Boolean {
        val tail = text.trim().takeLast(tailChars).lowercase()
        if (tail.isEmpty() || EXCLUSIONS.any { tail.contains(it) }) return false
        return INTENT_PHRASES.any { tail.contains(it) }
    }
}
