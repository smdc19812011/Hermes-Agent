package com.hermes.agent.ui.toolbox

import android.content.Context
import android.hardware.usb.UsbManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.agent.data.toolbox.InstallStatus
import com.hermes.agent.data.toolbox.SourceType
import com.hermes.agent.data.toolbox.ToolLibraryRepository
import com.hermes.agent.data.tools.toInfo
import com.hermes.agent.data.usb.DriverKind
import com.hermes.agent.data.usb.DriverMatch
import com.hermes.agent.data.usb.DriverProfile
import com.hermes.agent.data.usb.DriverProfileRepository
import com.hermes.agent.data.usb.UsbCatalog
import com.hermes.agent.data.usb.UsbIdsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ConnectedUsb(
    val deviceName: String,
    val title: String,
    val ids: String,
    val drivers: List<DriverMatch>,
    val missingPackages: List<String>
)

data class UsbDriversUiState(
    val devices: List<ConnectedUsb> = emptyList(),
    val profiles: List<DriverProfile> = emptyList(),
    val profileInput: String = "",
    val busy: String? = null,
    val message: String? = null
)

@HiltViewModel
class UsbDriversViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ids: UsbIdsRepository,
    private val profiles: DriverProfileRepository,
    private val library: ToolLibraryRepository
) : ViewModel() {

    private val _state = MutableStateFlow(UsbDriversUiState())
    val state: StateFlow<UsbDriversUiState> = _state.asStateFlow()

    init {
        combine(profiles.profiles, library.tools) { p, _ -> p }
            .onEach { p -> _state.update { it.copy(profiles = p) }; scan() }
            .launchIn(viewModelScope)
    }

    fun scan() = viewModelScope.launch {
        val manager = context.getSystemService(UsbManager::class.java) ?: return@launch
        val db = ids.get(allowDownload = manager.deviceList.isNotEmpty())
        val installed = library.tools.first()
            .filter { it.status == InstallStatus.INSTALLED && it.type == SourceType.PKG }.map { it.address }.toSet()
        val list = manager.deviceList.values.map { d ->
            val drivers = UsbCatalog.classify(d.toInfo(), _state.value.profiles)
            val name = listOfNotNull(db?.vendor(d.vendorId), db?.product(d.vendorId, d.productId)).joinToString(" ")
                .ifBlank { db?.className(d.deviceClass) ?: "USB device" }
            ConnectedUsb(
                deviceName = d.deviceName,
                title = name,
                ids = "%04x:%04x".format(d.vendorId, d.productId),
                drivers = drivers,
                missingPackages = drivers.filter { it.kind == DriverKind.TERMUX_PACK }.flatMap { it.packages }.filterNot { it in installed }.distinct()
            )
        }
        _state.update { it.copy(devices = list) }
    }

    fun installPackages(packages: List<String>) = op("Installing ${packages.joinToString()}…") {
        val results = packages.map { pkg -> library.install(library.add(pkg, SourceType.PKG).id) }
        scan()
        results.joinToString(" ") { r -> if (r.ok) "${r.tool.name} installed." else "${r.tool.name}: ${r.tool.message}" }
    }

    fun setProfileInput(v: String) = _state.update { it.copy(profileInput = v) }

    fun installProfile() = op("Installing driver profile…") {
        val p = profiles.install(_state.value.profileInput)
        _state.update { it.copy(profileInput = "") }
        "Installed \"${p.name}\" with commands ${p.commands.keys.joinToString()}."
    }

    fun removeProfile(name: String) = op(null) { profiles.remove(name); "Removed \"$name\"." }

    fun updateIds() = op("Downloading the USB ID database…") { "USB ID database updated (${ids.refresh()}).".also { scan() } }

    fun clearMessage() = _state.update { it.copy(message = null) }

    private fun op(label: String?, block: suspend () -> String) {
        if (_state.value.busy != null) return
        _state.update { it.copy(busy = label, message = null) }
        viewModelScope.launch {
            val msg = runCatching { block() }.getOrElse { e -> e.message ?: "Something went wrong." }
            _state.update { it.copy(busy = null, message = msg) }
        }
    }
}
