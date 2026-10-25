package app.opaquevault

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.opaquevault.security.PanicAction
import app.opaquevault.security.SecurityPreferences
import app.opaquevault.security.VaultEmergencyEraser

/** Optional user-added Quick Settings panic tile. Its action is configured inside the vault. */
class PanicTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        val action = SecurityPreferences(this).panicTileAction
        qsTile?.apply {
            state = if (action == PanicAction.OFF) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
            label = when (action) { PanicAction.OFF -> "Vault panic (off)"; PanicAction.LOCK -> "Panic lock"; PanicAction.ERASE -> "Panic erase" }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) subtitle = "Opaque Vault"
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val action = SecurityPreferences(this).panicTileAction
        when (action) {
            PanicAction.OFF -> Unit
            PanicAction.LOCK -> sendBroadcast(Intent(ACTION_PANIC).setPackage(packageName).putExtra(EXTRA_ACTION, action.name))
            PanicAction.ERASE -> {
                // Erase persistent keys even when no Activity/process session is open, then notify an
                // active Activity so any in-memory key copy is wiped immediately too.
                VaultEmergencyEraser.erase(this)
                sendBroadcast(Intent(ACTION_PANIC).setPackage(packageName).putExtra(EXTRA_ACTION, action.name))
            }
        }
        onStartListening()
    }

    companion object {
        const val ACTION_PANIC = "app.opaquevault.action.PANIC"
        const val EXTRA_ACTION = "panic_action"
    }
}
