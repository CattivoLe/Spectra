package alexcattivo.localchat.kit

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockTest {
    private class MemorySettings : SettingsStore {
        val values = mutableMapOf<String, Any>()
        override fun getBoolean(key: String, default: Boolean) = values[key] as? Boolean ?: default
        override fun putBoolean(key: String, value: Boolean) { values[key] = value }
        override fun getInt(key: String, default: Int) = values[key] as? Int ?: default
        override fun putInt(key: String, value: Int) { values[key] = value }
        override fun getString(key: String) = values[key] as? String
        override fun putString(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
    }

    private class FakeAuthenticator(var succeeds: Boolean = true, var available: Boolean = true) : Authenticating {
        val reasons = mutableListOf<AuthReason>()
        override fun isAvailable() = available
        override suspend fun authenticate(reason: AuthReason): Boolean { reasons += reason; return succeeds }
    }

    @Test fun enablingRequiresTheOwnerAndColdStartIsLocked() = runBlocking {
        val settings = MemorySettings()
        val auth = FakeAuthenticator(succeeds = false)
        val lock = AppLock(auth, settings)
        assertFalse(lock.enable())
        assertFalse(lock.isEnabled)
        auth.succeeds = true
        assertTrue(lock.enable())
        assertFalse(lock.isLocked)
        assertTrue(AppLock(auth, settings).isLocked)
    }

    @Test fun cannotEnableWithoutScreenLock() = runBlocking {
        val lock = AppLock(FakeAuthenticator(available = false), MemorySettings())
        assertFalse(lock.enable())
        assertFalse(lock.isEnabled)
    }

    @Test fun gracePeriodDecidesWhetherToLock() = runBlocking {
        var clock = 0L
        val lock = AppLock(FakeAuthenticator(), MemorySettings()) { clock }
        lock.enable()
        lock.gracePeriod = AppLock.GracePeriod.OneMinute
        lock.appDidEnterBackground()
        clock = 30_000
        lock.appDidBecomeActive()
        assertFalse("полминуты в фоне — ещё открыто", lock.isLocked)
        lock.appDidEnterBackground()
        clock += 61_000
        lock.appDidBecomeActive()
        assertTrue(lock.isLocked)
        lock.unlock()
        assertFalse(lock.isLocked)
    }

    @Test fun failedUnlockStaysLockedAndDisablingNeedsOwner() = runBlocking {
        val auth = FakeAuthenticator()
        val settings = MemorySettings()
        AppLock(auth, settings).enable()
        val lock = AppLock(auth, settings)
        auth.succeeds = false
        lock.unlock()
        assertTrue(lock.isLocked)
        assertFalse(lock.disable())
        assertTrue(lock.isEnabled)
        auth.succeeds = true
        assertTrue(lock.disable())
        assertFalse(lock.isLocked)
        assertEquals(AuthReason.Disable, auth.reasons.last())
    }
}
