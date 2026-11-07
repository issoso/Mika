package com.wangzan101.mika.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator

class McpDirectDriveSettingsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private fun settings() = McpSettings(tmp.root, AesGcmKeyCipher { key })

    @Test
    fun directDriveIsOffByDefaultAndPersistsAcrossRestarts() {
        val first = settings()
        assertFalse(first.directDriveEnabled.value)

        first.setDirectDriveEnabled(true)
        assertTrue(first.directDriveEnabled.value)
        assertTrue(settings().directDriveEnabled.value)

        first.setDirectDriveEnabled(false)
        assertFalse(settings().directDriveEnabled.value)
    }
}
