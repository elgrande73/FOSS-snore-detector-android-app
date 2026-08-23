package com.aistudio.snoredetector.afkwd.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log

/**
 * Data model representing a selectable audio input device for snoring detection.
 */
data class AudioInputDevice(
    val id: Int, // System device ID, or -1 for Built-in Phone Microphone
    val name: String,
    val typeDescription: String,
    val type: Int,
    val address: String = "",
    val isBluetooth: Boolean = false,
    val isBuiltIn: Boolean = false,
    val isUsb: Boolean = false,
    val isWired: Boolean = false
) {
    companion object {
        val PHONE_MIC = AudioInputDevice(
            id = -1,
            name = "Phone microphone",
            typeDescription = "Built-in Phone Microphone",
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) AudioDeviceInfo.TYPE_BUILTIN_MIC else 15,
            isBuiltIn = true
        )
        // Default device alias pointing directly to the Phone Microphone
        val DEFAULT_DEVICE = PHONE_MIC
    }
}

/**
 * Data model representing the evaluation of logical configured input vs. physical routed input.
 */
data class AudioRoutingEvaluation(
    val configuredDisplayName: String,
    val activeDisplayName: String,
    val isFallback: Boolean,
    val actualDeviceTypeName: String = "",
    val actualProductName: String = ""
)

/**
 * Manager utility for detecting, inspecting, filtering, and resolving audio input devices.
 * Distinguishes between all raw devices returned by Android AudioManager and devices that are
 * actually physically connected and usable as recording inputs for AudioRecord.
 */
object AudioInputManager {
    private const val TAG = "AudioInputManager"

    /**
     * Inspect and log complete hardware and HAL characteristics for an AudioDeviceInfo.
     */
    fun logDeviceDetails(device: AudioDeviceInfo, evaluationResult: String = "INSPECTED") {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return

        val addressStr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            device.address ?: "N/A"
        } else {
            "N/A (API < 28)"
        }

        val channelCountsStr = device.channelCounts.let {
            if (it.isEmpty()) "Standard/Unspecified" else it.joinToString(", ")
        }
        val channelMasksStr = device.channelMasks.let {
            if (it.isEmpty()) "Standard/Unspecified" else it.joinToString(", ") { mask -> "0x${mask.toString(16)}" }
        }
        val channelIndexMasksStr = device.channelIndexMasks.let {
            if (it.isEmpty()) "Standard/Unspecified" else it.joinToString(", ") { mask -> "0x${mask.toString(16)}" }
        }
        val sampleRatesStr = device.sampleRates.let {
            if (it.isEmpty()) "All/Resampled (Standard)" else it.joinToString(", ") { rate -> "${rate}Hz" }
        }
        val encodingsStr = device.encodings.let {
            if (it.isEmpty()) "Standard/Unspecified" else it.joinToString(", ")
        }

        Log.i(
            TAG,
            """
            |=== [Audio Input Device] Status: $evaluationResult ===
            |  * ID: ${device.id}
            |  * Product Name: "${device.productName}"
            |  * Type: ${device.type} (${getDeviceTypeName(device.type)})
            |  * Address: $addressStr
            |  * Direction: isSource=${device.isSource}, isSink=${device.isSink}
            |  * Channel Counts: [$channelCountsStr]
            |  * Channel Masks: [$channelMasksStr]
            |  * Channel Index Masks: [$channelIndexMasksStr]
            |  * Sample Rates: [$sampleRatesStr]
            |  * Encodings: [$encodingsStr]
            |======================================================
            """.trimMargin()
        )
    }

    /**
     * Find matching Communication AudioDeviceInfo on Android 12+ (API 31+).
     * Communication routing requires selecting an AudioDeviceInfo from AudioManager.getAvailableCommunicationDevices().
     */
    fun findMatchingCommunicationDevice(context: Context, targetDevice: AudioDeviceInfo?): AudioDeviceInfo? {
        if (targetDevice == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
            val commDevices = audioManager.availableCommunicationDevices
            Log.i(TAG, "Available Communication Devices count: ${commDevices.size}")
            for (comm in commDevices) {
                logDeviceDetails(comm, "AVAILABLE_COMMUNICATION_DEVICE")
            }

            // 1. Direct ID match
            val matchById = commDevices.firstOrNull { it.id == targetDevice.id }
            if (matchById != null) {
                Log.i(TAG, "Matched CommunicationDevice by ID: ${matchById.id} (\"${matchById.productName}\")")
                return matchById
            }

            // 2. Type & Address match (for Bluetooth peripherals)
            if (isBluetoothType(targetDevice.type)) {
                val targetAddress = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) targetDevice.address ?: "" else ""
                val matchByAddress = commDevices.firstOrNull {
                    isBluetoothType(it.type) && targetAddress.isNotBlank() && it.address == targetAddress
                }
                if (matchByAddress != null) {
                    Log.i(TAG, "Matched CommunicationDevice by Address: $targetAddress (\"${matchByAddress.productName}\")")
                    return matchByAddress
                }

                // 3. Fallback: match by Bluetooth type and name
                val matchByName = commDevices.firstOrNull {
                    isBluetoothType(it.type) && (
                        it.productName?.toString()?.equals(targetDevice.productName?.toString(), ignoreCase = true) == true ||
                        it.type == targetDevice.type
                    )
                }
                if (matchByName != null) {
                    Log.i(TAG, "Matched CommunicationDevice by Name/Type: ${matchByName.id} (\"${matchByName.productName}\")")
                    return matchByName
                }

                // 4. Any available Bluetooth SCO / BLE communication device
                val anyBtComm = commDevices.firstOrNull { isBluetoothType(it.type) }
                if (anyBtComm != null) {
                    Log.i(TAG, "Fallback: Matched first available Bluetooth CommunicationDevice: ${anyBtComm.id} (\"${anyBtComm.productName}\")")
                    return anyBtComm
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding matching communication device", e)
        }
        return null
    }

    /**
     * Enable Bluetooth communication audio routing (SCO/HFP) for microphone capture.
     * On Android 12+ (API 31+): Uses AudioManager.setCommunicationDevice().
     * On Android 6 to 11: Uses AudioManager.startBluetoothSco().
     */
    fun enableBluetoothCommunicationRouting(context: Context, targetDevice: AudioDeviceInfo?): Boolean {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val commDevice = findMatchingCommunicationDevice(context, targetDevice) ?: targetDevice
                if (commDevice != null) {
                    val result = audioManager.setCommunicationDevice(commDevice)
                    val currentComm = audioManager.communicationDevice
                    Log.i(
                        TAG,
                        """
                        |=== Bluetooth Communication Routing Activated (API 31+) ===
                        | Target Device: "${targetDevice?.productName}" (id=${targetDevice?.id}, type=${targetDevice?.type})
                        | Communication Device: "${commDevice.productName}" (id=${commDevice.id}, type=${commDevice.type})
                        | Result: $result
                        | Active Communication Device: "${currentComm?.productName}" (id=${currentComm?.id}, type=${currentComm?.type})
                        |===========================================================
                        """.trimMargin()
                    )
                    return result
                } else {
                    Log.w(TAG, "Could not resolve a valid CommunicationDevice for Bluetooth routing")
                }
            } else {
                // Legacy Android 6.0 to 11 (API 23 to 30) SCO start
                @Suppress("DEPRECATION")
                if (!audioManager.isBluetoothScoOn) {
                    Log.i(TAG, "Starting Bluetooth SCO audio link (API < 31)")
                    @Suppress("DEPRECATION")
                    audioManager.startBluetoothSco()
                    @Suppress("DEPRECATION")
                    audioManager.isBluetoothScoOn = true
                }
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error enabling Bluetooth communication routing", e)
        }
        return false
    }

    /**
     * Disable Bluetooth communication audio routing cleanly when switching to Built-in/USB or stopping.
     */
    fun disableBluetoothCommunicationRouting(context: Context) {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val activeComm = audioManager.communicationDevice
                if (activeComm != null) {
                    Log.i(TAG, "Clearing communication device (was: \"${activeComm.productName}\", id=${activeComm.id})")
                    audioManager.clearCommunicationDevice()
                }
            } else {
                @Suppress("DEPRECATION")
                if (audioManager.isBluetoothScoOn) {
                    Log.i(TAG, "Stopping Bluetooth SCO audio link (API < 31)")
                    @Suppress("DEPRECATION")
                    audioManager.stopBluetoothSco()
                    @Suppress("DEPRECATION")
                    audioManager.isBluetoothScoOn = false
                }
                @Suppress("DEPRECATION")
                if (audioManager.mode == AudioManager.MODE_IN_COMMUNICATION) {
                    @Suppress("DEPRECATION")
                    audioManager.mode = AudioManager.MODE_NORMAL
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error disabling Bluetooth communication routing", e)
        }
    }

    /**
     * Enumerate all actually connected and usable audio input devices detected by Android.
     * Evaluates every reported device, logs its complete HAL characteristics, filters out
     * non-mic / virtual / unpopulated vendor routes (e.g. unconnected Motorola line/headset ports),
     * and returns only verified usable input devices.
     */
    fun getAvailableInputDevices(context: Context): List<AudioInputDevice> {
        val deviceList = mutableListOf<AudioInputDevice>()
        // Always include the explicit phone microphone as the primary reference
        deviceList.add(AudioInputDevice.PHONE_MIC)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return deviceList
        }

        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (audioManager == null) {
                Log.w(TAG, "AudioManager not available on context")
                return deviceList
            }

            val isWiredHeadsetConnected = isWiredHeadsetActuallyConnected(audioManager)
            val allDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)

            Log.i(TAG, "Enumerating audio inputs: found ${allDevices.size} total input AudioDeviceInfo entries in AudioManager")

            for (device in allDevices) {
                // Must be an input source
                if (!device.isSource) {
                    logDeviceDetails(device, "SKIPPED: Not an audio source (isSource=false)")
                    continue
                }

                val type = device.type
                val isBt = isBluetoothType(type)
                val isBuiltIn = isBuiltInType(type)
                val isUsb = isUsbType(type)
                val isWired = isWiredType(type)

                // 1. Built-in microphones (phone mic array):
                // Built-in phone microphones (e.g., bottom mic, top mic, back mic) are all handled
                // through the explicit "Phone microphone" entry, which explicitly targets the built-in
                // microphone AudioDeviceInfo to ensure it is not superseded by USB/BT default routes.
                if (isBuiltIn) {
                    logDeviceDetails(device, "CONSOLIDATED: Built-in mic represented by explicit Phone microphone")
                    continue
                }

                // 2. Wired Headset validation:
                // Check if a 3.5mm/USB-C wired headset is actually physically inserted.
                // If not connected, this is an unpopulated HAL port (e.g. Motorola external mic HAL node).
                if (isWired && !isWiredHeadsetConnected) {
                    logDeviceDetails(device, "EXCLUDED: Wired headset port reported by HAL but no physical headset is connected")
                    continue
                }

                // 3. Exclude non-microphone virtual, system, or unsupported line-in endpoints:
                if (!isBt && !isUsb && !isWired) {
                    logDeviceDetails(
                        device,
                        "EXCLUDED: Non-microphone / virtual / vendor line-in route (${getDeviceTypeName(type)}, type=$type)"
                    )
                    continue
                }

                // 4. Extract clean display name
                val rawName = device.productName?.toString()?.trim()
                val address = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) device.address ?: "" else ""

                val displayName = when {
                    isUsb && !rawName.isNullOrBlank() && rawName != "Android" && !isGenericVendorLabel(rawName) -> rawName
                    isUsb -> "USB Audio Microphone"
                    isBt && !rawName.isNullOrBlank() && rawName != "Android" && !isGenericVendorLabel(rawName) -> rawName
                    isBt -> "Bluetooth Microphone (${getDeviceTypeName(type)})"
                    isWired && !rawName.isNullOrBlank() && rawName != "Android" && !isGenericVendorLabel(rawName) -> rawName
                    isWired -> "Wired Headset Microphone"
                    else -> getDeviceTypeName(type)
                }

                logDeviceDetails(device, "ACCEPTED: Selectable input candidate -> \"$displayName\"")

                val inputDevice = AudioInputDevice(
                    id = device.id,
                    name = displayName,
                    typeDescription = getDeviceTypeName(type),
                    type = type,
                    address = address,
                    isBluetooth = isBt,
                    isBuiltIn = false,
                    isUsb = isUsb,
                    isWired = isWired
                )

                // Prevent duplicate system device IDs
                if (deviceList.none { it.id == inputDevice.id }) {
                    deviceList.add(inputDevice)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error enumerating audio input devices", e)
        }

        Log.i(TAG, "Usable audio inputs available for selection: ${deviceList.size} (${deviceList.map { "${it.name} (id=${it.id}, type=${it.type})" }})")
        return deviceList
    }

    /**
     * Check if a wired headset is actually physically connected to the device.
     */
    private fun isWiredHeadsetActuallyConnected(audioManager: AudioManager): Boolean {
        // 1. Primary check: AudioManager legacy query
        @Suppress("DEPRECATION")
        if (audioManager.isWiredHeadsetOn) {
            return true
        }

        // 2. Output devices check (if headset or headphones are active in output devices)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val outputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                if (outputDevices.any { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES }) {
                    return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error checking output devices for wired headset: ${e.message}")
            }
        }

        return false
    }

    /**
     * Check if a product name is just a generic fallback label rather than a specific peripheral name.
     */
    fun isGenericVendorLabel(name: String): Boolean {
        val lower = name.lowercase().trim()
        return lower == "android" || lower == "external microphone" || lower == "audio device" || lower == "headset"
    }

    /**
     * Check if the target configuration represents the default Built-in Phone Microphone.
     */
    fun isPhoneMicrophoneSelection(targetId: Int, targetName: String): Boolean {
        return targetId == -1 ||
                targetName.isBlank() ||
                targetName.equals(AudioInputDevice.PHONE_MIC.name, ignoreCase = true) ||
                targetName.equals("Phone microphone", ignoreCase = true) ||
                targetName.equals("Default (Phone Microphone)", ignoreCase = true) ||
                targetName.contains("Phone", ignoreCase = true) ||
                targetName.contains("Built-in", ignoreCase = true)
    }

    /**
     * Evaluates whether the active audio hardware routing represents a normal resolution of the
     * user's requested logical device or a genuine fallback to a different device.
     */
    fun evaluateAudioRouting(
        configuredId: Int,
        configuredName: String,
        routedDevice: AudioDeviceInfo?
    ): AudioRoutingEvaluation {
        val isConfiguredPhoneMic = isPhoneMicrophoneSelection(configuredId, configuredName)
        val configuredDisplayName = if (isConfiguredPhoneMic) {
            AudioInputDevice.PHONE_MIC.name
        } else {
            configuredName.ifBlank { AudioInputDevice.PHONE_MIC.name }
        }

        if (routedDevice == null) {
            // When routedDevice is null (e.g. API < 23 or during initial startup), the standard Android
            // recording pipeline default is the built-in phone microphone.
            return if (isConfiguredPhoneMic) {
                AudioRoutingEvaluation(
                    configuredDisplayName = AudioInputDevice.PHONE_MIC.name,
                    activeDisplayName = AudioInputDevice.PHONE_MIC.name,
                    isFallback = false,
                    actualDeviceTypeName = "Internal Phone Microphone",
                    actualProductName = ""
                )
            } else {
                // User requested an external peripheral, but no routing could be established (fell back to default built-in mic)
                AudioRoutingEvaluation(
                    configuredDisplayName = configuredDisplayName,
                    activeDisplayName = AudioInputDevice.PHONE_MIC.name,
                    isFallback = true,
                    actualDeviceTypeName = "Internal Phone Microphone",
                    actualProductName = ""
                )
            }
        }

        val routedType = routedDevice.type
        val routedTypeName = getDeviceTypeName(routedType)
        val routedProductName = routedDevice.productName?.toString()?.trim() ?: ""
        val isRoutedBuiltIn = isBuiltInType(routedType)
        val isRoutedBt = isBluetoothType(routedType)
        val isRoutedUsb = isUsbType(routedType)
        val isRoutedWired = isWiredType(routedType)

        val routedPeripheralDisplayName = when {
            isRoutedUsb && routedProductName.isNotBlank() && routedProductName != "Android" && !isGenericVendorLabel(routedProductName) -> routedProductName
            isRoutedUsb -> "USB Audio Microphone"
            isRoutedBt && routedProductName.isNotBlank() && routedProductName != "Android" && !isGenericVendorLabel(routedProductName) -> routedProductName
            isRoutedBt -> "Bluetooth Microphone (${getDeviceTypeName(routedType)})"
            isRoutedWired && routedProductName.isNotBlank() && routedProductName != "Android" && !isGenericVendorLabel(routedProductName) -> routedProductName
            isRoutedWired -> "Wired Headset Microphone"
            else -> routedProductName.takeIf { it.isNotBlank() } ?: routedTypeName
        }

        if (isConfiguredPhoneMic) {
            // User requested Phone Microphone
            if (isRoutedBuiltIn) {
                // Normal resolution: Android resolved "Phone microphone" to the device's built-in microphone
                // (e.g. TYPE_BUILTIN_MIC, which may report productName "motorola edge", "Pixel 8", etc.).
                // This is normal hardware resolution, NOT a fallback.
                return AudioRoutingEvaluation(
                    configuredDisplayName = AudioInputDevice.PHONE_MIC.name,
                    activeDisplayName = AudioInputDevice.PHONE_MIC.name,
                    isFallback = false,
                    actualDeviceTypeName = routedTypeName,
                    actualProductName = routedProductName
                )
            } else {
                // Genuine fallback: User requested Phone microphone, but an external peripheral took over routing
                return AudioRoutingEvaluation(
                    configuredDisplayName = AudioInputDevice.PHONE_MIC.name,
                    activeDisplayName = routedPeripheralDisplayName,
                    isFallback = true,
                    actualDeviceTypeName = routedTypeName,
                    actualProductName = routedProductName
                )
            }
        } else {
            // User requested a specific external peripheral (Bluetooth, USB, Wired headset)
            val isDirectIdMatch = routedDevice.id == configuredId
            val isCategoryMatch = (configuredDisplayName.contains("Bluetooth", ignoreCase = true) && isRoutedBt) ||
                    (configuredDisplayName.contains("USB", ignoreCase = true) && isRoutedUsb) ||
                    (configuredDisplayName.contains("Wired", ignoreCase = true) && isRoutedWired) ||
                    (routedProductName.isNotBlank() && routedProductName.equals(configuredDisplayName, ignoreCase = true))

            if (isDirectIdMatch || isCategoryMatch) {
                return AudioRoutingEvaluation(
                    configuredDisplayName = configuredDisplayName,
                    activeDisplayName = configuredDisplayName,
                    isFallback = false,
                    actualDeviceTypeName = routedTypeName,
                    actualProductName = routedProductName
                )
            } else {
                // Genuine fallback: Requested peripheral was not routed, fell back to built-in mic or another route
                val fallbackDisplayName = if (isRoutedBuiltIn) {
                    AudioInputDevice.PHONE_MIC.name
                } else {
                    routedPeripheralDisplayName
                }
                return AudioRoutingEvaluation(
                    configuredDisplayName = configuredDisplayName,
                    activeDisplayName = fallbackDisplayName,
                    isFallback = true,
                    actualDeviceTypeName = routedTypeName,
                    actualProductName = routedProductName
                )
            }
        }
    }

    /**
     * Find the actual AudioDeviceInfo corresponding to the device's built-in microphone.
     */
    fun getBuiltInMicrophoneDeviceInfo(context: Context): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            val builtIn = devices.firstOrNull { it.isSource && isBuiltInType(it.type) }
            if (builtIn != null) {
                logDeviceDetails(builtIn, "RESOLVED as Built-in Phone Microphone AudioDeviceInfo")
                return builtIn
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error retrieving built-in microphone AudioDeviceInfo", e)
        }
        return null
    }

    /**
     * Find matching AudioDeviceInfo by targetId or targetName from currently connected inputs.
     * When targetId is -1 or matches Phone microphone, explicitly returns the built-in phone microphone
     * AudioDeviceInfo so AudioRecord.setPreferredDevice() binds explicitly to the built-in mic instead
     * of defaulting to connected USB or Bluetooth peripherals.
     */
    fun findMatchingDeviceInfo(context: Context, targetId: Int, targetName: String): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return null
        }

        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)

            val isPhoneMicRequested = targetId == -1 ||
                    targetName.isBlank() ||
                    targetName.equals(AudioInputDevice.PHONE_MIC.name, ignoreCase = true) ||
                    targetName.equals("Phone microphone", ignoreCase = true) ||
                    targetName.equals("Default (Phone Microphone)", ignoreCase = true) ||
                    targetName.contains("Phone", ignoreCase = true) ||
                    targetName.contains("Built-in", ignoreCase = true)

            // 1. If explicit Phone / Built-in microphone was requested
            if (isPhoneMicRequested) {
                val builtIn = devices.firstOrNull { it.isSource && isBuiltInType(it.type) }
                if (builtIn != null) {
                    logDeviceDetails(builtIn, "EXPLICIT MATCH: Target is Phone Built-in Microphone (id=${builtIn.id})")
                    return builtIn
                }
                Log.w(TAG, "Built-in microphone AudioDeviceInfo not found in GET_DEVICES_INPUTS")
                return null
            }

            // 2. Try matching by system device ID
            val matchById = devices.firstOrNull { it.isSource && it.id == targetId }
            if (matchById != null) {
                logDeviceDetails(matchById, "MATCHED by ID ($targetId)")
                return matchById
            }

            // 3. Fallback: match by productName, typeDescription, or peripheral category
            if (targetName.isNotBlank()) {
                val matchByName = devices.firstOrNull {
                    it.isSource && (
                        it.productName?.toString()?.equals(targetName, ignoreCase = true) == true ||
                        getDeviceTypeName(it.type).equals(targetName, ignoreCase = true) ||
                        (isUsbType(it.type) && targetName.contains("USB", ignoreCase = true)) ||
                        (isBluetoothType(it.type) && targetName.contains("Bluetooth", ignoreCase = true))
                    )
                }
                if (matchByName != null) {
                    logDeviceDetails(matchByName, "MATCHED by Name (\"$targetName\") -> id=${matchByName.id}")
                    return matchByName
                }
            }

            // 4. Target peripheral disconnected / not found: fallback explicitly to Built-in Mic
            val fallbackBuiltIn = devices.firstOrNull { it.isSource && isBuiltInType(it.type) }
            if (fallbackBuiltIn != null) {
                Log.w(TAG, "Requested device (id=$targetId, name=\"$targetName\") not found. Falling back to Phone Microphone (id=${fallbackBuiltIn.id})")
                return fallbackBuiltIn
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving audio input device", e)
        }

        Log.w(TAG, "No matching connected AudioDeviceInfo found for id=$targetId, name=\"$targetName\"")
        return null
    }

    fun getDeviceTypeName(type: Int): String {
        return when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Internal Phone Microphone"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth Headset / Mask (SCO)"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth Audio Device"
            AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE Headset Microphone"
            AudioDeviceInfo.TYPE_BLE_SPEAKER -> "BLE Microphone"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset Microphone"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired Headphones"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Headset Microphone"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB Audio Microphone"
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB Audio Accessory"
            AudioDeviceInfo.TYPE_LINE_ANALOG -> "Analog Line-in"
            AudioDeviceInfo.TYPE_LINE_DIGITAL -> "Digital Line-in"
            AudioDeviceInfo.TYPE_AUX_LINE -> "Auxiliary Audio Line"
            AudioDeviceInfo.TYPE_BUS -> "System Bus Audio"
            AudioDeviceInfo.TYPE_TELEPHONY -> "Telephony RX/TX"
            AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "Remote Submix Loopback"
            AudioDeviceInfo.TYPE_FM_TUNER -> "FM Tuner"
            AudioDeviceInfo.TYPE_TV_TUNER -> "TV Tuner"
            AudioDeviceInfo.TYPE_IP -> "IP Audio Stream"
            AudioDeviceInfo.TYPE_HEARING_AID -> "Hearing Aid Microphone"
            AudioDeviceInfo.TYPE_DOCK -> "Dock Audio"
            else -> "Audio Input (Type $type)"
        }
    }

    fun isBluetoothType(type: Int): Boolean {
        if (type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) {
            return true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (type == AudioDeviceInfo.TYPE_BLE_HEADSET || type == AudioDeviceInfo.TYPE_BLE_SPEAKER) {
                return true
            }
        }
        return false
    }

    fun isBuiltInType(type: Int): Boolean {
        return type == AudioDeviceInfo.TYPE_BUILTIN_MIC ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE)
    }

    fun isUsbType(type: Int): Boolean {
        return type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && type == AudioDeviceInfo.TYPE_USB_ACCESSORY)
    }

    fun isWiredType(type: Int): Boolean {
        return type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
    }
}


