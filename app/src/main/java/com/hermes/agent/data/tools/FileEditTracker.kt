package com.hermes.agent.data.tools

import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Singleton

/**
 * Remembers, per conversation, the content hash of each file [ReadFileTool] last showed the model, so
 * [EditFileTool]/[WriteFileTool] can refuse to touch a file the model hasn't seen — or that changed since —
 * the same safety rule Claude Code's own Edit/Write tools use. In memory only; doesn't need to survive a restart.
 */
@Singleton
class FileEditTracker @javax.inject.Inject constructor() {
    private val seen = ConcurrentHashMap<String, String>()

    private fun key(conversationId: String, path: String) = "$conversationId|${path.trim()}"
    private fun hash(content: String): String =
        MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** Call after successfully showing or writing [content] for [path], so later edits are allowed. */
    fun record(conversationId: String, path: String, content: String) {
        seen[key(conversationId, path)] = hash(content)
    }

    fun hasRead(conversationId: String, path: String): Boolean = seen.containsKey(key(conversationId, path))

    /** True if [currentContent] matches what was last read/written — i.e. nothing touched the file since. */
    fun matches(conversationId: String, path: String, currentContent: String): Boolean =
        seen[key(conversationId, path)] == hash(currentContent)
}
