package com.hermes.agent.data.tools

import com.hermes.agent.domain.tools.AgentTool
import com.hermes.agent.domain.tools.Capability
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ToolRegistry @Inject constructor(
    networkInfo: NetworkInfoTool,
    lanScan: LanScanTool,
    portScan: PortScanTool,
    dnsLookup: DnsLookupTool,
    tcpPing: TcpPingTool,
    http: HttpRequestTool,
    search: WebSearchTool,
    btStatus: BluetoothStatusTool,
    btScan: BluetoothScanTool,
    bleRead: BleReadTool,
    usbDevices: UsbDevicesTool,
    usbAccess: UsbRequestAccessTool,
    shell: ShellTool,
    listTools: ListInstalledToolsTool,
    installTool: InstallToolTool,
    tcpSend: TcpSendTool,
    udpSend: UdpSendTool,
    wakeOnLan: WakeOnLanTool,
    listen: NetworkListenTool,
    usbSerial: UsbSerialTool,
    usbTransfer: UsbTransferTool,
    bleWrite: BleWriteTool,
    bleListen: BleListenTool,
    btSerial: BluetoothSerialTool,
    usbHid: UsbHidTool,
    usbPrint: UsbPrintTool,
    usbStorage: UsbStorageTool,
    usbMidi: UsbMidiTool,
    usbDriverInstall: UsbDriverInstallTool,
    usbDriverCommand: UsbDriverCommandTool,
    postFile: PostFileTool,
    imageSearch: ImageSearchTool,
    generateImage: GenerateImageTool,
    textToSpeech: TextToSpeechTool,
    useAttachment: UseAttachmentTool
) {
    val all: List<AgentTool> = listOf(
        networkInfo, lanScan, portScan, dnsLookup, tcpPing, http, search,
        tcpSend, udpSend, wakeOnLan, listen,
        btStatus, btScan, bleRead, bleWrite, bleListen, btSerial,
        usbDevices, usbAccess, usbSerial, usbTransfer, usbHid, usbPrint, usbStorage, usbMidi, usbDriverInstall, usbDriverCommand,
        postFile, imageSearch, generateImage, textToSpeech,
        shell, listTools, installTool, useAttachment
    )

    fun enabled(capabilities: Set<Capability>): List<AgentTool> = all.filter { it.capability in capabilities }
}

/** Holds tool calls waiting for the user's Allow/Deny in the chat. */
@Singleton
class ToolApprovals @Inject constructor() {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val allowedInChat = ConcurrentHashMap.newKeySet<String>()

    fun isAllowedInChat(conversationId: String, tool: String) = "$conversationId|$tool" in allowedInChat

    suspend fun await(callId: String, conversationId: String, tool: String): Boolean {
        val answer = CompletableDeferred<String>()
        pending[callId] = answer
        try {
            val choice = answer.await()
            if (choice == "session") allowedInChat += "$conversationId|$tool"
            return choice == "once" || choice == "session"
        } finally {
            pending.remove(callId)
        }
    }

    fun answer(callId: String, choice: String): Boolean = pending[callId]?.complete(choice) == true
}
