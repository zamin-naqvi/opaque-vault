package app.opaquevault.security

import android.content.Context

/** One fail-closed path used by duress, dead-man, panic tile, and explicit erase. */
object VaultEmergencyEraser {
    fun erase(context: Context) {
        val app = context.applicationContext
        runCatching { VaultKeyManager(app).cryptoErase() }
        runCatching { BiometricKeyManager(app).clear() }
        runCatching { NfcKeyManager(app).clear() }
        runCatching { DuressManager(app).clear() }
        runCatching { EncryptedIntruderLog(app).clear(deleteKey = true) }
        runCatching { SecurityPreferences(app).clearAll() }
        runCatching { LauncherIdentityManager(app).apply(LauncherIdentity.VAULT) }
    }
}
