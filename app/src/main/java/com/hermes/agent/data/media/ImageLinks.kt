package com.hermes.agent.data.media

/** Markdown images ![alt](url) and bare links ending in an image extension. */
private val IMAGE_LINK = Regex(
    "!\\[[^\\]]*]\\((https?://[^)\\s]+)\\)|(?<![(\\w])(https?://\\S+?\\.(?:png|jpe?g|gif|webp)(?:\\?[^\\s)\"']*[^\\s)\"'.,])?)(?=[\\s)\"'.,]|$)",
    RegexOption.IGNORE_CASE
)

fun imageLinks(text: String): List<String> =
    IMAGE_LINK.findAll(text).map { it.groups[1]?.value ?: it.groups[2]!!.value }.distinct().toList()
