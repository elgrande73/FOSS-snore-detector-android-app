package com.aistudio.snoredetector.afkwd

import android.media.AudioDeviceInfo
import com.aistudio.snoredetector.afkwd.audio.AudioInputDevice
import com.aistudio.snoredetector.afkwd.audio.AudioInputManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioInputManagerTest {

    @Test
    fun testDefaultDeviceAttributes() {
        val defaultDevice = AudioInputDevice.DEFAULT_DEVICE
        assertEquals(-1, defaultDevice.id)
        assertEquals("Phone microphone", defaultDevice.name)
        assertTrue(defaultDevice.isBuiltIn)
        assertFalse(defaultDevice.isBluetooth)
        assertFalse(defaultDevice.isUsb)
        assertFalse(defaultDevice.isWired)
    }

    @Test
    fun testDeviceTypeClassification() {
        // USB
        assertTrue(AudioInputManager.isUsbType(AudioDeviceInfo.TYPE_USB_DEVICE))
        assertTrue(AudioInputManager.isUsbType(AudioDeviceInfo.TYPE_USB_HEADSET))
        assertFalse(AudioInputManager.isUsbType(AudioDeviceInfo.TYPE_BUILTIN_MIC))

        // Bluetooth
        assertTrue(AudioInputManager.isBluetoothType(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
        assertTrue(AudioInputManager.isBluetoothType(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            assertTrue(AudioInputManager.isBluetoothType(AudioDeviceInfo.TYPE_BLE_HEADSET))
            assertTrue(AudioInputManager.isBluetoothType(AudioDeviceInfo.TYPE_BLE_SPEAKER))
        }
        assertFalse(AudioInputManager.isBluetoothType(AudioDeviceInfo.TYPE_USB_DEVICE))

        // Built-in
        assertTrue(AudioInputManager.isBuiltInType(AudioDeviceInfo.TYPE_BUILTIN_MIC))
        assertFalse(AudioInputManager.isBuiltInType(AudioDeviceInfo.TYPE_USB_DEVICE))

        // Wired
        assertTrue(AudioInputManager.isWiredType(AudioDeviceInfo.TYPE_WIRED_HEADSET))
        assertTrue(AudioInputManager.isWiredType(AudioDeviceInfo.TYPE_WIRED_HEADPHONES))
        assertFalse(AudioInputManager.isWiredType(AudioDeviceInfo.TYPE_BUILTIN_MIC))
    }

    @Test
    fun testDeviceTypeNameDescriptions() {
        assertEquals("Internal Phone Microphone", AudioInputManager.getDeviceTypeName(AudioDeviceInfo.TYPE_BUILTIN_MIC))
        assertEquals("USB Audio Microphone", AudioInputManager.getDeviceTypeName(AudioDeviceInfo.TYPE_USB_DEVICE))
        assertEquals("USB Headset Microphone", AudioInputManager.getDeviceTypeName(AudioDeviceInfo.TYPE_USB_HEADSET))
        assertEquals("Bluetooth Headset / Mask (SCO)", AudioInputManager.getDeviceTypeName(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
        assertEquals("Wired Headset Microphone", AudioInputManager.getDeviceTypeName(AudioDeviceInfo.TYPE_WIRED_HEADSET))
        assertEquals("Analog Line-in", AudioInputManager.getDeviceTypeName(AudioDeviceInfo.TYPE_LINE_ANALOG))
        assertEquals("System Bus Audio", AudioInputManager.getDeviceTypeName(AudioDeviceInfo.TYPE_BUS))
        assertEquals("Remote Submix Loopback", AudioInputManager.getDeviceTypeName(AudioDeviceInfo.TYPE_REMOTE_SUBMIX))
    }

    @Test
    fun testPhoneMicrophoneSelectionRecognition() {
        assertTrue(AudioInputManager.isPhoneMicrophoneSelection(-1, "Phone microphone"))
        assertTrue(AudioInputManager.isPhoneMicrophoneSelection(-1, ""))
        assertTrue(AudioInputManager.isPhoneMicrophoneSelection(-1, "Default (Phone Microphone)"))
        assertTrue(AudioInputManager.isPhoneMicrophoneSelection(-1, "Built-in Microphone"))
        assertFalse(AudioInputManager.isPhoneMicrophoneSelection(2, "Galaxy Buds2 Pro"))
        assertFalse(AudioInputManager.isPhoneMicrophoneSelection(5, "USB Condenser Mic"))
    }

    @Test
    fun testEvaluateAudioRoutingWithNullDevice() {
        // When user requested phone microphone and routedDevice is null, it resolves cleanly to phone mic without fallback
        val evalPhone = AudioInputManager.evaluateAudioRouting(-1, "Phone microphone", null)
        assertEquals("Phone microphone", evalPhone.configuredDisplayName)
        assertEquals("Phone microphone", evalPhone.activeDisplayName)
        assertFalse(evalPhone.isFallback)

        // When user requested external device and routedDevice is null, it falls back to phone mic
        val evalUsb = AudioInputManager.evaluateAudioRouting(10, "USB Mic", null)
        assertEquals("USB Mic", evalUsb.configuredDisplayName)
        assertEquals("Phone microphone", evalUsb.activeDisplayName)
        assertTrue(evalUsb.isFallback)
    }
}

