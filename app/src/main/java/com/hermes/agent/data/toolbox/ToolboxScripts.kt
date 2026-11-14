package com.hermes.agent.data.toolbox

import com.hermes.agent.data.runtime.HermesScripts.STATE_DIR
import com.hermes.agent.data.runtime.HermesScripts.shellQuote

/** Install/remove scripts for the shared tool library, run in Termux. Pure so bash can check them in tests. */
object ToolboxScripts {
    const val TOOLS_DIR = "$STATE_DIR/tools"
    /** Prepended wherever the AI or user runs commands, so library tools resolve by name. */
    const val PATH_EXPORT = "export PATH=\"$TOOLS_DIR/bin:\$PATH\""

    private val header = listOf(
        "set -e",
        "export DEBIAN_FRONTEND=noninteractive",
        "T=\"$TOOLS_DIR\"",
        "mkdir -p \"\$T/bin\" \"\$T/pkgs\" \"\$T/src\"",
        "APT=\"apt-get -y -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold\"",
        // Stale package lists are the usual cause of "Unable to locate package"; refresh once and retry.
        "apt_install() { \$APT install \"\$@\" || { apt-get update && \$APT install \"\$@\"; }; }",
        "need() { command -v \"\$1\" >/dev/null 2>&1 || apt_install \"\$2\"; }"
    )

    /** [downloadUrl] is the resolved asset for GitHub releases (looked up by the app), else the entry's address. */
    fun install(tool: LibraryTool, downloadUrl: String? = null): String {
        val q = shellQuote(tool.address)
        val body = when (tool.type) {
            SourceType.PKG -> listOf("echo \"==> Installing Termux package ${tool.address}\"", "apt_install $q")
            SourceType.PIP -> listOf("need pip python", "echo \"==> pip install ${tool.address}\"", "pip install --upgrade $q")
            SourceType.NPM -> listOf("need npm nodejs", "echo \"==> npm install -g ${tool.address}\"", "npm install -g $q")
            SourceType.GITHUB_RELEASE, SourceType.URL -> download(tool, downloadUrl ?: tool.address)
            SourceType.GIT_CLONE -> listOf(
                "need git git",
                "d=\"\$T/src/${tool.id}\"",
                "if [ -d \"\$d/.git\" ]; then git -C \"\$d\" pull --ff-only; else git clone --depth 1 $q \"\$d\"; fi",
                "find \"\$d\" -maxdepth 2 -type f -perm -u+x -not -path '*/.git/*' | while read -r f; do ln -sf \"\$f\" \"\$T/bin/\$(basename \"\$f\")\"; done",
                "echo \"==> Cloned to \$d\""
            )
        }
        return (header + body + listOf("echo \"==> Done. Commands in the shared tool folder:\"", "ls \"\$T/bin\"")).joinToString("\n")
    }

    private fun download(tool: LibraryTool, url: String): List<String> {
        val file = url.substringBefore('?').trimEnd('/').substringAfterLast('/').ifBlank { tool.name }
        val qf = shellQuote(file)
        return listOf(
            "need curl curl",
            "d=\"\$T/pkgs/${tool.id}\"",
            "rm -rf \"\$d\" && mkdir -p \"\$d\" && cd \"\$d\"",
            "echo \"==> Downloading $file\"",
            "curl -fL --retry 3 --connect-timeout 20 -o $qf ${shellQuote(url)}",
            "case $qf in",
            "  *.tar.gz|*.tgz|*.tar.xz|*.tar.bz2|*.tar) tar -xf $qf && rm -f $qf ;;",
            "  *.zip) need unzip unzip; unzip -oq $qf && rm -f $qf ;;",
            "  *.gz) gunzip -f $qf ;;",
            "  *) chmod +x $qf; ln -sf \"\$d/$file\" \"\$T/bin/${tool.name}\" ;;",
            "esac",
            // Archives: expose executables and anything under a bin/ directory.
            "find \"\$d\" -type f \\( -perm -u+x -o -path '*/bin/*' \\) | while read -r f; do chmod +x \"\$f\"; " +
                "n=\$(basename \"\$f\"); [ \"\$n\" = \"$file\" ] || ln -sf \"\$f\" \"\$T/bin/\$n\"; done"
        )
    }

    fun remove(tool: LibraryTool): String {
        val q = shellQuote(tool.address)
        val body = when (tool.type) {
            SourceType.PKG -> listOf("apt-get remove -y $q")
            SourceType.PIP -> listOf("pip uninstall -y ${shellQuote(tool.name)}")
            SourceType.NPM -> listOf("npm uninstall -g ${shellQuote(tool.address.substringBeforeLast('@').ifEmpty { tool.address })}")
            SourceType.GITHUB_RELEASE, SourceType.URL -> listOf("rm -rf \"\$T/pkgs/${tool.id}\"")
            SourceType.GIT_CLONE -> listOf("rm -rf \"\$T/src/${tool.id}\"")
        }
        // Drop links whose targets were just deleted.
        return (header + body + listOf("find \"\$T/bin\" -xtype l -delete", "echo \"==> Removed ${tool.name}\"")).joinToString("\n")
    }

    fun listCommands() = "ls \"$TOOLS_DIR/bin\" 2>/dev/null"
}
