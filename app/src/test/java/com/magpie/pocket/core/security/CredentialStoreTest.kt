package com.magpie.pocket.core.security

import android.content.SharedPreferences
import com.magpie.pocket.core.model.ConnectionConfig
import com.magpie.pocket.core.model.DataMode
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class CredentialStoreTest {
    private val live = ConnectionConfig(
        "test-live", "test", DataMode.LIVE, "https://magpie.example.com",
        "fixture-web-key", cfAccessClientId = "fixture-client", cfAccessClientSecret = "fixture-secret"
    )

    @Test fun unavailableEncryptionKeepsDemoAndRefusesCredentials() {
        var erased = false
        val store = CredentialStore({ throw IllegalStateException("provider detail") }, { erased = true })
        assertTrue(erased)
        assertEquals(DataMode.DEMO, store.getConnection().mode)
        assertThrows(CredentialStorageException::class.java) { store.saveConnection(live) }
        store.clearCredentials()
    }

    @Test fun failedLegacyCleanupPreventsOpeningAndSaving() {
        var opened = false
        val store = CredentialStore({ opened = true; TestPreferences().prefs }, { error("cleanup failed") })
        assertFalse(opened)
        assertEquals(DataMode.DEMO, store.getConnection().mode)
        assertThrows(CredentialStorageException::class.java) { store.saveConnection(live) }
    }

    @Test fun credentialsRoundTripOnlyThroughProvidedEncryptedStore() {
        val encrypted = TestPreferences()
        val store = CredentialStore({ encrypted.prefs }, {})
        store.saveConnection(live)
        assertEquals(live, store.getConnection())
        assertEquals(1, encrypted.commits)
    }

    @Test fun failedCommitDoesNotReportSuccessfulPersistence() {
        val encrypted = TestPreferences().apply { commitSucceeds = false }
        val store = CredentialStore({ encrypted.prefs }, {})
        assertThrows(CredentialStorageException::class.java) { store.saveConnection(live) }
    }

    @Test fun storageProviderDetailsAreNotExposed() {
        val encrypted = TestPreferences().apply { failEdit = true }
        val store = CredentialStore({ encrypted.prefs }, {})
        val error = assertThrows(CredentialStorageException::class.java) { store.saveConnection(live) }
        assertFalse(error.message.orEmpty().contains("private-provider-detail"))
        assertNull(error.cause)
    }

    @Test fun unreadableEncryptedDataReturnsDemoAndRefusesSubsequentWrites() {
        val encrypted = TestPreferences().apply { failRead = true }
        val store = CredentialStore({ encrypted.prefs }, {})
        assertEquals(DataMode.DEMO, store.getConnection().mode)
        assertThrows(CredentialStorageException::class.java) { store.saveConnection(live) }
    }

    @Test fun clearingRemovesAllSavedFieldsAndReportsWriteFailure() {
        val encrypted = TestPreferences()
        val store = CredentialStore({ encrypted.prefs }, {})
        store.saveConnection(live)
        store.clearCredentials()
        assertTrue(encrypted.values.isEmpty())
        assertEquals(DataMode.DEMO, store.getConnection().mode)
        encrypted.commitSucceeds = false
        assertThrows(CredentialStorageException::class.java) { store.clearCredentials() }
    }
}

private class TestPreferences {
    val values = mutableMapOf<String, Any?>()
    var commitSucceeds = true
    var commits = 0
    var failRead = false
    var failEdit = false
    private val pending = mutableMapOf<String, Any?>()
    private var clear = false

    private val editor: SharedPreferences.Editor = Proxy.newProxyInstance(
        SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java)
    ) { proxy, method, args ->
        when (method.name) {
            "putString", "putBoolean" -> { pending[args[0] as String] = args[1]; proxy }
            "clear" -> { clear = true; proxy }
            "commit" -> {
                commits++
                if (commitSucceeds) {
                    if (clear) values.clear()
                    values.putAll(pending)
                }
                pending.clear(); clear = false
                commitSucceeds
            }
            else -> error("Unexpected editor operation: ${method.name}")
        }
    } as SharedPreferences.Editor

    val prefs: SharedPreferences = Proxy.newProxyInstance(
        SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)
    ) { _, method, args ->
        when (method.name) {
            "edit" -> { if (failEdit) error("private-provider-detail"); editor }
            "getString", "getBoolean" -> {
                if (failRead) error("private-provider-detail")
                values[args[0] as String] ?: args[1]
            }
            else -> error("Unexpected preference operation: ${method.name}")
        }
    } as SharedPreferences
}
