package com.hermes.agent.data.toolbox

import androidx.annotation.Keep

@Keep
enum class SourceType(val displayName: String) {
    PKG("Termux package"),
    PIP("Python package (pip)"),
    NPM("Node package (npm)"),
    GITHUB_RELEASE("GitHub release"),
    GIT_CLONE("Git repository"),
    URL("Download URL")
}

@Keep
enum class InstallStatus { PENDING, INSTALLING, INSTALLED, FAILED }

/** One entry in the shared tool library. Persisted as JSON: names must survive R8. */
@Keep
data class LibraryTool(
    val id: String = "",
    val type: SourceType = SourceType.PKG,
    val address: String = "",
    val name: String = "",
    val status: InstallStatus = InstallStatus.PENDING,
    val message: String = "",
    val updatedAt: Long = 0L
)

data class ParsedSource(val type: SourceType, val address: String, val name: String)

data class Preset(val label: String, val address: String)

object ToolSource {
    val PRESETS = listOf(
        Preset("nmap", "nmap"),
        Preset("Android SDK tools (adb, fastboot)", "android-tools"),
        Preset("Python", "python"),
        Preset("Node.js", "nodejs"),
        Preset("git", "git"),
        Preset("curl", "curl"),
        Preset("jq", "jq"),
        Preset("ffmpeg", "ffmpeg"),
        Preset("iproute2 (ip)", "iproute2"),
        Preset("Termux:API commands", "termux-api"),
        Preset("OpenSSH", "openssh")
    )

    /** Google's SDK downloads are x86-64 desktop builds; on a phone the same tools come from Termux. */
    private val ANDROID_SDK_ALIASES = setOf("android sdk", "androidsdk", "android-sdk", "platform-tools", "platform tools", "adb", "fastboot", "sdk")

    private val PKG_NAME = Regex("^[a-z0-9][a-z0-9+.-]*$")
    private val PIP_SPEC = Regex("^[A-Za-z0-9][A-Za-z0-9_.\\-\\[\\],]*([=<>!~]=?[A-Za-z0-9_.*+-]+)?$")
    private val NPM_SPEC = Regex("^(@[a-z0-9][a-z0-9._-]*/)?[a-z0-9][a-z0-9._-]*(@[A-Za-z0-9._^~<>=-]+)?$")
    private val GITHUB = Regex("^(?:https?://)?(?:www\\.)?github\\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(?:\\.git)?(/.*)?$")
    private val OWNER_REPO = Regex("^([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)$")

    /** Works out what an address points at; [forced] overrides the guess. Throws with a readable message. */
    fun parse(input: String, forced: SourceType? = null): ParsedSource {
        val raw = input.trim()
        require(raw.isNotEmpty()) { "Enter a package name, GitHub repository or URL." }
        if (raw.lowercase() in ANDROID_SDK_ALIASES) return ParsedSource(SourceType.PKG, "android-tools", "android-tools")

        val prefixed = Regex("^(pkg|pip|npm|git|url|github):(.+)$", RegexOption.IGNORE_CASE).find(raw)
        val explicit = prefixed?.groupValues?.get(1)?.lowercase()?.let {
            when (it) {
                "pkg" -> SourceType.PKG; "pip" -> SourceType.PIP; "npm" -> SourceType.NPM
                "git" -> SourceType.GIT_CLONE; "github" -> SourceType.GITHUB_RELEASE; else -> SourceType.URL
            }
        }
        val value = prefixed?.groupValues?.get(2)?.trim() ?: raw
        val type = forced ?: explicit ?: guess(value)

        return when (type) {
            SourceType.PKG -> {
                require(PKG_NAME.matches(value)) { "\"$value\" isn't a valid Termux package name." }
                ParsedSource(type, value, value)
            }
            SourceType.PIP -> {
                require(PIP_SPEC.matches(value)) { "\"$value\" isn't a valid pip package." }
                ParsedSource(type, value, value.split('=', '<', '>', '!', '~', '[').first())
            }
            SourceType.NPM -> {
                require(NPM_SPEC.matches(value)) { "\"$value\" isn't a valid npm package." }
                ParsedSource(type, value, value.removePrefix("@").substringAfter('/').substringBefore('@'))
            }
            SourceType.GITHUB_RELEASE -> {
                val (owner, repo) = GITHUB.find(value)?.destructured?.let { (o, r, _) -> o to r }
                    ?: OWNER_REPO.find(value)?.destructured?.let { (o, r) -> o to r }
                    ?: throw IllegalArgumentException("Use owner/repo or a github.com repository link.")
                ParsedSource(type, "$owner/$repo", repo)
            }
            SourceType.GIT_CLONE -> {
                val url = if (OWNER_REPO.matches(value)) "https://github.com/$value.git" else value
                require(url.startsWith("https://") || url.startsWith("http://")) { "Git repositories need an https:// address." }
                ParsedSource(type, url, url.trimEnd('/').substringAfterLast('/').removeSuffix(".git"))
            }
            SourceType.URL -> {
                require(value.startsWith("https://") || value.startsWith("http://")) { "Download addresses need https:// or http://." }
                val file = value.substringBefore('?').trimEnd('/').substringAfterLast('/')
                ParsedSource(type, value, baseName(file))
            }
        }
    }

    private fun guess(value: String): SourceType {
        val github = GITHUB.find(value)
        return when {
            github != null && "/releases/download/" in (github.groupValues[3]) -> SourceType.URL
            github != null && (value.endsWith(".git") || "/tree/" in github.groupValues[3]) -> SourceType.GIT_CLONE
            github != null -> SourceType.GITHUB_RELEASE
            value.startsWith("http://") || value.startsWith("https://") ->
                if (value.endsWith(".git")) SourceType.GIT_CLONE else SourceType.URL
            OWNER_REPO.matches(value) -> SourceType.GITHUB_RELEASE
            else -> SourceType.PKG
        }
    }

    /** "jq-linux-arm64" -> "jq", "ripgrep-14.1.0-aarch64-unknown-linux-musl.tar.gz" -> "ripgrep". */
    fun baseName(file: String): String {
        val noExt = file.replace(Regex("\\.(tar\\.gz|tar\\.xz|tar\\.bz2|tgz|zip|gz|tar|bin|sh|py)$"), "")
        val cut = Regex("[-_.](v?\\d|linux|android|termux|aarch64|arm64|armv\\w*|x86_64|amd64|musl|gnu|static|unknown)").find(noExt)
        return (if (cut != null && cut.range.first > 0) noExt.substring(0, cut.range.first) else noExt).ifBlank { file }
    }

    fun archAliases(abi: String): List<String> = when (abi) {
        "arm64-v8a" -> listOf("aarch64", "arm64", "armv8")
        "armeabi-v7a" -> listOf("armv7", "armhf", "arm")
        "x86_64" -> listOf("x86_64", "amd64", "x64")
        "x86" -> listOf("i686", "i386", "x86", "386")
        else -> listOf(abi)
    }

    private val SKIP = Regex("(?i)(\\.(sha\\d*|sha\\d+sum|md5|sig|asc|pem|sbom|json|txt|deb|rpm|apk|msi|exe|dmg|pkg|appimage)$|checksums|darwin|macos|apple|windows|win64|win32|freebsd|netbsd|openbsd|source)")

    /**
     * Picks the release asset most likely to run on this phone: built for its CPU, preferring
     * Android/Termux or static/musl Linux builds, since ordinary glibc Linux binaries won't start on Android.
     */
    fun pickAsset(names: List<String>, abi: String): String? {
        val arch = archAliases(abi)
        return names
            .filterNot { SKIP.containsMatchIn(it) }
            .mapNotNull { name ->
                val n = name.lowercase()
                if (arch.none { a -> Regex("(^|[^a-z0-9])${Regex.escape(a)}([^a-z0-9]|$)").containsMatchIn(n) }) return@mapNotNull null
                var score = 10
                if ("android" in n || "termux" in n) score += 8
                if ("musl" in n || "static" in n) score += 4
                if ("linux" in n) score += 2
                if ("gnu" in n) score -= 1
                name to score
            }
            .maxByOrNull { it.second }?.first
    }
}
