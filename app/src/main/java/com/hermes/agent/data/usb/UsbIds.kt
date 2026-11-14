package com.hermes.agent.data.usb

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Parsed linux-usb.org usb.ids: vendor/product names and class/subclass/protocol names. */
class UsbIds private constructor(
    private val vendors: Map<Int, String>,
    private val products: Map<Long, String>,
    private val classes: Map<Int, String>,
    private val subclasses: Map<Int, String>,
    private val protocols: Map<Int, String>,
    val version: String?
) {
    fun vendor(vid: Int): String? = vendors[vid]
    fun product(vid: Int, pid: Int): String? = products[(vid.toLong() shl 16) or pid.toLong()]
    fun className(c: Int): String? = classes[c]
    fun subclassName(c: Int, s: Int): String? = subclasses[(c shl 8) or s]
    fun protocolName(c: Int, s: Int, p: Int): String? = protocols[(c shl 16) or (s shl 8) or p]

    companion object {
        fun parse(text: String): UsbIds {
            val vendors = HashMap<Int, String>(4096)
            val products = HashMap<Long, String>(32768)
            val classes = HashMap<Int, String>()
            val subclasses = HashMap<Int, String>()
            val protocols = HashMap<Int, String>()
            var version: String? = null
            var section = ' ' // 'v' vendors, 'c' classes, other sections are skipped
            var vendor = -1
            var cls = -1
            var sub = -1
            for (line in text.lineSequence()) {
                if (line.startsWith("#")) {
                    if (version == null && line.startsWith("# Version:")) version = line.substringAfter(':').trim()
                    continue
                }
                if (line.isBlank()) continue
                when {
                    line.startsWith("C ") -> {
                        section = 'c'
                        cls = line.substring(2, 4).toIntOrNull(16) ?: continue
                        classes[cls] = line.substring(4).trim()
                    }
                    !line.startsWith("\t") && line.length > 5 && line[4] == ' ' && line.substring(0, 4).toIntOrNull(16) != null -> {
                        section = 'v'
                        vendor = line.substring(0, 4).toInt(16)
                        vendors[vendor] = line.substring(4).trim()
                    }
                    !line.startsWith("\t") -> section = ' ' // AT, HID, R, BIAS, PHY, HUT, L, HCC, VT sections
                    section == 'v' && line.startsWith("\t") && !line.startsWith("\t\t") -> {
                        val id = line.substring(1, 5).toIntOrNull(16) ?: continue
                        products[(vendor.toLong() shl 16) or id.toLong()] = line.substring(5).trim()
                    }
                    section == 'c' && line.startsWith("\t\t") -> {
                        val p = line.substring(2, 4).toIntOrNull(16) ?: continue
                        protocols[(cls shl 16) or (sub shl 8) or p] = line.substring(4).trim()
                    }
                    section == 'c' && line.startsWith("\t") -> {
                        sub = line.substring(1, 3).toIntOrNull(16) ?: continue
                        subclasses[(cls shl 8) or sub] = line.substring(3).trim()
                    }
                }
            }
            return UsbIds(vendors, products, classes, subclasses, protocols, version)
        }
    }
}

/** Downloads usb.ids once (refreshed after 60 days) and keeps it parsed in memory. */
@Singleton
class UsbIdsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient
) {
    private val file get() = File(context.filesDir, "usb.ids")
    private val lock = Mutex()
    @Volatile private var cached: UsbIds? = null

    /** Null if the database isn't downloaded yet and the download fails (e.g. offline). */
    suspend fun get(allowDownload: Boolean = true): UsbIds? = lock.withLock {
        cached?.let { return@withLock it }
        withContext(Dispatchers.IO) {
            val stale = !file.exists() || System.currentTimeMillis() - file.lastModified() > 60L * 24 * 3600 * 1000
            if (stale && allowDownload) runCatching { download() }
            file.takeIf { it.exists() }?.let { UsbIds.parse(it.readText()) }
        }?.also { cached = it }
    }

    suspend fun refresh(): String = lock.withLock {
        withContext(Dispatchers.IO) {
            download()
            UsbIds.parse(file.readText()).also { cached = it }.version ?: "unknown version"
        }
    }

    private fun download() {
        val client = okHttpClient.newBuilder().callTimeout(60, TimeUnit.SECONDS).build()
        val sources = listOf("https://raw.githubusercontent.com/usbids/usbids/master/usb.ids", "http://www.linux-usb.org/usb.ids")
        val text = sources.firstNotNullOfOrNull { url ->
            runCatching {
                client.newCall(Request.Builder().url(url).build()).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
            }.getOrNull()?.takeIf { it.contains("\nC 03") }
        } ?: throw IllegalStateException("Couldn't download the USB ID database.")
        val tmp = File(file.parentFile, "usb.ids.tmp").apply { writeText(text) }
        tmp.renameTo(file)
    }
}
