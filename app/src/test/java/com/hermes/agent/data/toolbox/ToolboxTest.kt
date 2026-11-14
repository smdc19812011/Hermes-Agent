package com.hermes.agent.data.toolbox

import com.hermes.agent.data.runtime.HermesScripts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class ToolboxTest {

    @Test
    fun parsesEveryAddressForm() {
        fun p(s: String) = ToolSource.parse(s).let { Triple(it.type, it.address, it.name) }
        assertEquals(Triple(SourceType.PKG, "nmap", "nmap"), p("nmap"))
        assertEquals(Triple(SourceType.PKG, "android-tools", "android-tools"), p("Android SDK"))
        assertEquals(Triple(SourceType.PKG, "android-tools", "android-tools"), p("adb"))
        assertEquals(Triple(SourceType.PIP, "speedtest-cli==2.1.3", "speedtest-cli"), p("pip:speedtest-cli==2.1.3"))
        assertEquals(Triple(SourceType.NPM, "@scope/tool@1.2.0", "tool"), p("npm:@scope/tool@1.2.0"))
        assertEquals(Triple(SourceType.GITHUB_RELEASE, "sharkdp/fd", "fd"), p("https://github.com/sharkdp/fd"))
        assertEquals(Triple(SourceType.GITHUB_RELEASE, "BurntSushi/ripgrep", "ripgrep"), p("BurntSushi/ripgrep"))
        assertEquals(SourceType.GIT_CLONE, p("https://github.com/termux/termux-packages.git").first)
        assertEquals(SourceType.GIT_CLONE, p("git:owner/repo").first)
        assertEquals(Triple(SourceType.URL, "https://github.com/jqlang/jq/releases/download/jq-1.7.1/jq-linux-arm64", "jq"),
            p("https://github.com/jqlang/jq/releases/download/jq-1.7.1/jq-linux-arm64"))
        assertEquals(SourceType.URL, p("https://example.com/tools/scan.sh").first)
        assertEquals("scan", p("https://example.com/tools/scan.sh").third)
    }

    @Test
    fun rejectsUnsafeOrInvalidInput() {
        listOf("nmap; rm -rf /", "pip:\$(evil)", "ftp://x/y", "").forEach { bad ->
            assertTrue(bad, runCatching { ToolSource.parse(bad) }.isFailure)
        }
        assertTrue(runCatching { ToolSource.parse("nmap", SourceType.URL) }.isFailure)
    }

    @Test
    fun picksTheAndroidOrStaticArm64Asset() {
        // Asset names as published by ripgrep, fd, jq and gh releases.
        val ripgrep = listOf(
            "ripgrep-14.1.1-aarch64-apple-darwin.tar.gz", "ripgrep-14.1.1-aarch64-unknown-linux-gnu.tar.gz",
            "ripgrep-14.1.1-aarch64-unknown-linux-gnu.tar.gz.sha256", "ripgrep-14.1.1-x86_64-unknown-linux-musl.tar.gz",
            "ripgrep-14.1.1-x86_64-pc-windows-msvc.zip", "ripgrep_14.1.1-1_amd64.deb"
        )
        assertEquals("ripgrep-14.1.1-aarch64-unknown-linux-gnu.tar.gz", ToolSource.pickAsset(ripgrep, "arm64-v8a"))
        assertEquals("ripgrep-14.1.1-x86_64-unknown-linux-musl.tar.gz", ToolSource.pickAsset(ripgrep, "x86_64"))

        val withAndroid = listOf("tool-linux-arm64.tar.gz", "tool-android-arm64.tar.gz", "tool-linux-arm64-musl.tar.gz")
        assertEquals("tool-android-arm64.tar.gz", ToolSource.pickAsset(withAndroid, "arm64-v8a"))
        assertEquals("tool-linux-arm64-musl.tar.gz", ToolSource.pickAsset(withAndroid - "tool-android-arm64.tar.gz", "arm64-v8a"))

        assertEquals("jq-linux-arm64", ToolSource.pickAsset(listOf("jq-linux-amd64", "jq-linux-arm64", "jq-macos-arm64", "jq-windows-amd64.exe"), "arm64-v8a"))
        // "arm" must not match "arm64" for 32-bit phones.
        assertNull(ToolSource.pickAsset(listOf("tool-linux-arm64.tar.gz"), "armeabi-v7a"))
        assertNull(ToolSource.pickAsset(listOf("tool-x86_64.tar.gz", "tool-darwin-arm64.tar.gz"), "arm64-v8a"))
    }

    @Test
    fun baseNamesFromAssetFiles() {
        assertEquals("ripgrep", ToolSource.baseName("ripgrep-14.1.1-aarch64-unknown-linux-gnu.tar.gz"))
        assertEquals("jq", ToolSource.baseName("jq-linux-arm64"))
        assertEquals("gh", ToolSource.baseName("gh_2.62.0_linux_arm64.tar.gz"))
        assertEquals("scan", ToolSource.baseName("scan.sh"))
    }

    // ---- install scripts through real bash, with apt/curl/pip stubbed ----

    private lateinit var home: File
    private lateinit var bin: File
    private lateinit var downloads: File

    @Before
    fun setUp() {
        home = Files.createTempDirectory("toolbox-home").toFile()
        bin = File(home, "stubbin").apply { mkdirs() }
        downloads = File(home, "downloads").apply { mkdirs() }
        stub("apt-get", "echo \"apt-get \$*\" >> \"\$HOME/calls\"")
        stub("pip", "echo \"pip \$*\" >> \"\$HOME/calls\"")
        stub("npm", "echo \"npm \$*\" >> \"\$HOME/calls\"")
        // curl -fL ... -o FILE URL: serve FILE from the downloads folder by the URL's last segment.
        stub("curl", "out=''; url=''; while [ \$# -gt 0 ]; do case \"\$1\" in -o) out=\"\$2\"; shift 2;; -*) shift;; *) url=\"\$1\"; shift;; esac; done; " +
            "cp \"\$HOME/downloads/\${url##*/}\" \"\$out\"")
    }

    private fun stub(name: String, body: String) = File(bin, name).apply { writeText("#!/bin/bash\n$body\n"); setExecutable(true) }

    private fun run(script: String): Pair<Int, String> {
        val p = ProcessBuilder("bash").directory(home).redirectErrorStream(true).apply {
            environment()["HOME"] = home.absolutePath
            environment()["PATH"] = "${bin.absolutePath}:${System.getenv("PATH")}"
        }.start()
        p.outputStream.use { it.write(HermesScripts.wrapForStdin(script).toByteArray()) }
        val out = p.inputStream.bufferedReader().readText()
        assertTrue(p.waitFor(60, TimeUnit.SECONDS))
        return p.exitValue() to out
    }

    private val toolsBin get() = File(home, ".hermes-android/tools/bin")

    @Test
    fun packageInstallsQuoteTheirArguments() {
        run(ToolboxScripts.install(LibraryTool("nmap", SourceType.PKG, "nmap", "nmap"))).let { assertEquals(it.second, 0, it.first) }
        run(ToolboxScripts.install(LibraryTool("s", SourceType.PIP, "speedtest-cli==2.1.3", "speedtest-cli"))).let { assertEquals(it.second, 0, it.first) }
        val calls = File(home, "calls").readText()
        assertTrue(calls, calls.contains("install nmap"))
        assertTrue(calls, calls.contains("pip install --upgrade speedtest-cli==2.1.3"))
    }

    @Test
    fun singleBinaryDownloadIsLinkedByToolName() {
        File(downloads, "jq-linux-arm64").writeText("#!/bin/sh\necho jq-ok\n")
        val tool = LibraryTool("jq", SourceType.URL, "https://github.com/jqlang/jq/releases/download/jq-1.7.1/jq-linux-arm64", "jq")
        val (code, out) = run(ToolboxScripts.install(tool))
        assertEquals(out, 0, code)
        val (c2, o2) = run("${ToolboxScripts.PATH_EXPORT}\njq")
        assertEquals(o2, 0, c2)
        assertEquals("jq-ok", o2.trim())
    }

    @Test
    fun archiveReleaseExposesItsExecutables() {
        // Build a release-style archive: tool-1.0/bin/tool plus a README.
        val stage = Files.createTempDirectory("stage").toFile()
        File(stage, "tool-1.0/bin").mkdirs()
        File(stage, "tool-1.0/bin/tool").apply { writeText("#!/bin/sh\necho tool-ok\n"); setExecutable(true) }
        File(stage, "tool-1.0/README.md").writeText("docs")
        val tar = ProcessBuilder("tar", "-czf", File(downloads, "tool-1.0-aarch64-linux-musl.tar.gz").absolutePath, "-C", stage.absolutePath, "tool-1.0").start()
        assertEquals(0, tar.waitFor())

        val tool = LibraryTool("tool", SourceType.GITHUB_RELEASE, "owner/tool", "tool")
        val (code, out) = run(ToolboxScripts.install(tool, "https://github.com/owner/tool/releases/download/v1/tool-1.0-aarch64-linux-musl.tar.gz"))
        assertEquals(out, 0, code)
        assertTrue(File(toolsBin, "tool").exists())
        assertTrue("README must not be linked", !File(toolsBin, "README.md").exists())
        assertEquals("tool-ok", run("${ToolboxScripts.PATH_EXPORT}\ntool").second.trim())

        // Removing deletes the files and the now-dangling link.
        val (rc, rout) = run(ToolboxScripts.remove(tool))
        assertEquals(rout, 0, rc)
        assertTrue(!File(toolsBin, "tool").exists())
    }

    @Test
    fun failedDownloadFailsTheScript() {
        val tool = LibraryTool("missing", SourceType.URL, "https://example.com/missing.bin", "missing")
        assertTrue(run(ToolboxScripts.install(tool)).first != 0)
    }
}
