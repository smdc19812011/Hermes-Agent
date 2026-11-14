package com.hermes.agent.data.usb

/** Descriptor facts about one connected device, independent of Android classes so matching is testable. */
data class UsbInfo(
    val vid: Int,
    val pid: Int,
    val deviceClass: Int,
    val interfaces: List<Itf>,
    val serialDriver: String? = null // set when usb-serial-for-android recognises the chip
) {
    data class Itf(val cls: Int, val subclass: Int, val protocol: Int)

    fun hasInterface(cls: Int, subclass: Int? = null, protocol: Int? = null) = interfaces.any {
        it.cls == cls && (subclass == null || it.subclass == subclass) && (protocol == null || it.protocol == protocol)
    } || (interfaces.isEmpty() && deviceClass == cls)
}

enum class DriverKind { PROFILE, BUILT_IN, SYSTEM, TERMUX_PACK, RAW }

data class DriverMatch(
    val kind: DriverKind,
    val title: String,
    /** The tool the AI should call, when there is one. */
    val tool: String?,
    val note: String,
    /** Termux packages for TERMUX_PACK matches. */
    val packages: List<String> = emptyList(),
    val needsRoot: Boolean = false
)

/** Decides how to drive a device: installed profile, built-in class driver, Android itself, a Termux pack, or raw I/O. */
object UsbCatalog {
    fun classify(info: UsbInfo, profiles: List<DriverProfile> = emptyList()): List<DriverMatch> = buildList {
        profiles.filter { it.matches(info.vid, info.pid) }.forEach {
            add(DriverMatch(DriverKind.PROFILE, "Driver profile: ${it.name}", "usb_driver_command",
                "Commands: ${it.commands.keys.joinToString().ifEmpty { "none" }}"))
        }
        if (info.serialDriver != null || info.hasInterface(0x02, 0x02) || info.hasInterface(0x0A)) {
            add(DriverMatch(DriverKind.BUILT_IN, "Serial (${info.serialDriver ?: "CDC-ACM"})", "usb_serial", "Send and receive at any baud rate."))
        }
        if (info.hasInterface(0x03)) {
            val kind = when {
                info.hasInterface(0x03, 0x01, 0x01) -> "keyboard"
                info.hasInterface(0x03, 0x01, 0x02) -> "mouse"
                else -> "HID device"
            }
            add(DriverMatch(DriverKind.BUILT_IN, "HID ($kind)", "usb_hid",
                "Read input reports, send output and feature reports. Claiming a keyboard or mouse pauses it as a system input device."))
        }
        if (info.hasInterface(0x07)) add(DriverMatch(DriverKind.BUILT_IN, "Printer", "usb_print", "Send raw print data (ESC/POS, PCL, plain text) and read its device ID."))
        if (info.hasInterface(0x08, 0x06)) add(DriverMatch(DriverKind.BUILT_IN, "USB drive (SCSI)", "usb_storage", "List, read and write files on FAT32 drives."))
        if (info.hasInterface(0x01, 0x03)) add(DriverMatch(DriverKind.BUILT_IN, "MIDI", "usb_midi", "Send and receive MIDI messages."))
        if (info.hasInterface(0x01, 0x01) || info.hasInterface(0x01, 0x02)) {
            add(DriverMatch(DriverKind.SYSTEM, "USB audio", null, "Android routes USB audio itself; it appears as an audio output/input."))
        }
        if (info.hasInterface(0x0E)) add(DriverMatch(DriverKind.SYSTEM, "USB camera (UVC)", null, "Phones with external-camera support show it in camera apps; there's no app-level driver here."))
        if (info.hasInterface(0x02, 0x06) || info.hasInterface(0x02, 0x0D) || info.hasInterface(0xE0, 0x01, 0x03) || info.hasInterface(0xEF, 0x04)) {
            add(DriverMatch(DriverKind.SYSTEM, "USB network adapter", "network_info", "Android brings it up as a network interface; use the network tools."))
        }
        if (info.hasInterface(0x06, 0x01)) {
            add(DriverMatch(DriverKind.TERMUX_PACK, "Camera/phone (MTP/PTP)", null, "Android's Files app usually opens these. libmtp tools are available in Termux.", listOf("libmtp")))
        }
        if (info.hasInterface(0x0B)) {
            add(DriverMatch(DriverKind.TERMUX_PACK, "Smart card reader (CCID)", null, "pcscd with the CCID driver; needs a rooted phone for direct USB access.", listOf("pcscd", "libccid"), needsRoot = true))
        }
        if (info.hasInterface(0xFF, 0x42, 0x01) || info.hasInterface(0xFF, 0x42, 0x03)) {
            add(DriverMatch(DriverKind.TERMUX_PACK, "Android device (adb/fastboot)", "run_command", "Termux's android-tools provides adb and fastboot.", listOf("android-tools", "termux-api")))
        }
        if (info.vid == 0x05AC) {
            add(DriverMatch(DriverKind.TERMUX_PACK, "Apple device", null, "libimobiledevice and usbmuxd; direct USB access needs a rooted phone.", listOf("libimobiledevice", "usbmuxd"), needsRoot = true))
        }
        if (info.hasInterface(0xFE, 0x01)) {
            add(DriverMatch(DriverKind.RAW, "Firmware update mode (DFU)", "usb_transfer", "No DFU flasher is packaged for Termux; a driver profile or raw control transfers can drive it."))
        }
        add(DriverMatch(DriverKind.RAW, "Raw USB I/O", "usb_transfer", "Bulk/interrupt transfers on any interface; works for every device."))
    }
}
