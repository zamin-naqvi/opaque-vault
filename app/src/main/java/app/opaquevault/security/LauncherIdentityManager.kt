package app.opaquevault.security

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Switches between manifest-declared launcher aliases without changing the package, signing identity,
 * private data, or cryptographic state. The desired alias is enabled before old aliases are disabled,
 * so a partial launcher failure cannot strand the user without an entry point.
 */
class LauncherIdentityManager(context: Context) {
    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager

    fun apply(identity: LauncherIdentity): Boolean {
        val desired = component(identity)
        try {
            set(desired, enabled = true)
        } catch (_: Exception) {
            return false
        }
        var complete = true
        LauncherIdentity.entries
            .filterNot { it == identity }
            .forEach { other ->
                try {
                    set(component(other), enabled = false)
                } catch (_: Exception) {
                    complete = false
                }
            }
        return complete
    }

    private fun set(component: ComponentName, enabled: Boolean) {
        packageManager.setComponentEnabledSetting(
            component,
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
    }

    private fun component(identity: LauncherIdentity): ComponentName {
        val className = when (identity) {
            LauncherIdentity.VAULT -> "$PACKAGE_NAME.launcher.VaultLauncher"
            LauncherIdentity.NOTES -> "$PACKAGE_NAME.launcher.NotesLauncher"
            LauncherIdentity.CALCULATOR -> "$PACKAGE_NAME.launcher.CalculatorLauncher"
        }
        return ComponentName(PACKAGE_NAME, className)
    }

    companion object {
        private const val PACKAGE_NAME = "app.opaquevault"
    }
}
