package com.aistudio.snoredetector.afkwd

import android.Manifest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionResultTest {
    @Test
    fun deniedBluetoothKeepsGrantedMicrophone() {
        val results = mapOf(Manifest.permission.BLUETOOTH_CONNECT to false)

        assertTrue(permissionAfterResult(results, Manifest.permission.RECORD_AUDIO, current = true))
        assertFalse(permissionAfterResult(results, Manifest.permission.BLUETOOTH_CONNECT, current = false))
    }

    @Test
    fun resultReplacesCurrentStateWhenPermissionWasRequested() {
        val results = mapOf(Manifest.permission.RECORD_AUDIO to true)

        assertTrue(permissionAfterResult(results, Manifest.permission.RECORD_AUDIO, current = false))
    }

    @Test
    fun revokedPermissionInResultIsDenied() {
        val results = mapOf(Manifest.permission.RECORD_AUDIO to false)

        assertFalse(permissionAfterResult(results, Manifest.permission.RECORD_AUDIO, current = true))
    }
}
