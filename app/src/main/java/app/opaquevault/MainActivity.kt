package app.opaquevault

import android.app.assist.AssistContent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import app.opaquevault.security.PanicAction
import app.opaquevault.security.ThemeMode
import app.opaquevault.ui.DeviceAuthenticationPurpose
import app.opaquevault.ui.NfcKeyPurpose
import app.opaquevault.ui.OpaqueVaultApp
import app.opaquevault.ui.theme.OpaqueVaultTheme
import app.opaquevault.vault.SharedImageIntents
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : FragmentActivity() {
    private lateinit var controller: VaultController
    private val handler = Handler(Looper.getMainLooper())
    private val lockRunnable = Runnable {
        if (::controller.isInitialized) controller.lock()
    }
    private val inactivityRunnable = Runnable { onInactivityTimeout() }
    private var biometricPrompt: BiometricPrompt? = null
    private var authenticationPurpose: DeviceAuthenticationPurpose? = null
    private var allowInsecureQaScreenshots = false
    private val sensorManager by lazy { getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    private val nfcAdapter by lazy { NfcAdapter.getDefaultAdapter(this) }
    private var shakeHits = 0
    private var firstShakeAt = 0L
    private var faceDownSince = 0L
    private var lastPanicAt = 0L
    private val panicSensorListener = object : SensorEventListener {
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        override fun onSensorChanged(event: SensorEvent) {
            val action = controller.state.settings.panicGestureAction
            if (action == PanicAction.OFF || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
            val now = android.os.SystemClock.elapsedRealtime()
            val x = event.values[0]; val y = event.values[1]; val z = event.values[2]
            val force = kotlin.math.sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
            if (force >= 2.7f) {
                if (now - firstShakeAt > 1_200L) { firstShakeAt = now; shakeHits = 0 }
                shakeHits++
                if (shakeHits >= 3) triggerPanic(action, now)
            }
            if (z <= -8.5f && kotlin.math.abs(x) < 4f && kotlin.math.abs(y) < 4f) {
                if (faceDownSince == 0L) faceDownSince = now
                if (now - faceDownSince >= 2_000L) triggerPanic(action, now)
            } else faceDownSince = 0L
        }
    }

    private val panicReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = runCatching { PanicAction.valueOf(intent.getStringExtra(PanicTileService.EXTRA_ACTION).orEmpty()) }
                .getOrDefault(PanicAction.LOCK)
            lifecycleScope.launch { controller.performPanicAction(action) }
        }
    }

    /** Locks the instant the screen turns off (power button or timeout), if enabled in Settings. */
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (::controller.isInitialized && controller.state.settings.lockOnScreenOff) controller.lock()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hardenWindow()
        controller = VaultController(applicationContext)
        ContextCompat.registerReceiver(
            this,
            screenOffReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        ContextCompat.registerReceiver(
            this,
            panicReceiver,
            IntentFilter(PanicTileService.ACTION_PANIC),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // A recreated activity must not re-queue a share that was already handled.
        if (savedInstanceState == null) acceptSharedImages(intent)

        setContent {
            val settings = controller.state.settings
            val dark = when (settings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            DisposableEffect(dark) {
                // Status/navigation bar icons must contrast with the chosen theme, not the system one.
                val style = if (dark) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            val rootView = LocalView.current
            DisposableEffect(rootView) {
                rootView.filterTouchesWhenObscured = true
                onDispose { rootView.filterTouchesWhenObscured = false }
            }
            OpaqueVaultTheme(themeMode = settings.themeMode, dynamicColor = settings.dynamicColor) {
                OpaqueVaultApp(
                    controller = controller,
                    allowInsecureQaScreenshots = allowInsecureQaScreenshots,
                    onRequestDeviceAuthentication = ::requestDeviceAuthentication,
                    onRequestNfcKey = ::requestNfcKey,
                )
            }
        }
    }

    /** singleTask: a Gallery share reaches the existing vault session instead of a second copy. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptSharedImages(intent)
    }

    override fun onStart() {
        handler.removeCallbacks(lockRunnable)
        controller.externalActivityPending = false
        super.onStart()
    }

    override fun onResume() {
        super.onResume()
        controller.refreshPermissions()
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(panicSensorListener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        scheduleInactivityLock()
    }

    override fun onPause() {
        runCatching { nfcAdapter?.disableReaderMode(this) }
        sensorManager.unregisterListener(panicSensorListener)
        handler.removeCallbacks(inactivityRunnable)
        super.onPause()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        scheduleInactivityLock()
    }

    override fun onStop() {
        super.onStop()
        if (isChangingConfigurations) {
            controller.lock()
            return
        }
        // Never weaken the user's lock delay for a picker or Android settings screen. Picker results
        // are queued while locked; backup/restore prompts wait until authentication succeeds again.
        handler.removeCallbacks(lockRunnable)
        val delay = controller.autoLockDelayMillis()
        if (delay == 0L) controller.lock() else handler.postDelayed(lockRunnable, delay)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenOffReceiver) }
        runCatching { unregisterReceiver(panicReceiver) }
        handler.removeCallbacks(lockRunnable)
        handler.removeCallbacks(inactivityRunnable)
        biometricPrompt?.cancelAuthentication()
        runCatching { nfcAdapter?.disableReaderMode(this) }
        sensorManager.unregisterListener(panicSensorListener)
        controller.close()
        super.onDestroy()
    }

    /** Queues shared images; nothing is read until the user unlocks and confirms the import. */
    private fun acceptSharedImages(intent: Intent?) {
        val uris = SharedImageIntents.extractImageUris(intent, packageName)
        if (uris.isNotEmpty()) controller.queueImports(uris)
    }

    private fun scheduleInactivityLock() {
        handler.removeCallbacks(inactivityRunnable)
        val delay = controller.inactivityLockMillis()
        if (delay > 0) handler.postDelayed(inactivityRunnable, delay)
    }

    private fun onInactivityTimeout() {
        if (controller.state.phase != VaultController.Phase.UNLOCKED) return
        if (controller.isBusy) {
            // Never interrupt an import, backup or wipe the user is waiting on.
            handler.postDelayed(inactivityRunnable, BUSY_RECHECK_MILLIS)
            return
        }
        controller.lock()
    }

    private fun requestDeviceAuthentication(purpose: DeviceAuthenticationPurpose) {
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG
        if (BiometricManager.from(this).canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            controller.markDeviceAuthenticationUnavailable()
            return
        }
        val cipher = try {
            when (purpose) {
                DeviceAuthenticationPurpose.UNLOCK -> controller.prepareBiometricUnlockCipher()
                DeviceAuthenticationPurpose.ENABLE_SETTING -> controller.prepareBiometricEnrollmentCipher()
            }
        } catch (error: Exception) {
            controller.reportMessage(error.message ?: "The hardware-bound biometric key is unavailable.")
            return
        }
        authenticationPurpose = purpose
        val executor = ContextCompat.getMainExecutor(this)
        biometricPrompt = BiometricPrompt(
            this,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    val authenticatedCipher = result.cryptoObject?.cipher
                    val completedPurpose = authenticationPurpose
                    authenticationPurpose = null
                    if (authenticatedCipher == null) {
                        controller.reportMessage("Android did not return an authenticated cryptographic operation.")
                        return
                    }
                    lifecycleScope.launch {
                        when (completedPurpose) {
                            DeviceAuthenticationPurpose.UNLOCK -> controller.unlockWithBiometric(authenticatedCipher)
                            DeviceAuthenticationPurpose.ENABLE_SETTING -> controller.completeBiometricEnrollment(authenticatedCipher)
                            null -> Unit
                        }
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    authenticationPurpose = null
                }
            },
        )
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(if (purpose == DeviceAuthenticationPurpose.UNLOCK) "Unlock Opaque Vault" else "Protect a biometric vault key")
            .setSubtitle("A strong biometric authorizes the Android Keystore key")
            .setAllowedAuthenticators(authenticators)
            .setNegativeButtonText("Cancel")
            .build()
        biometricPrompt?.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
    }

    private fun requestNfcKey(purpose: NfcKeyPurpose) {
        val adapter = nfcAdapter
        if (adapter == null || !adapter.isEnabled) {
            controller.reportMessage("NFC is unavailable or turned off on this phone.")
            return
        }
        controller.reportMessage(if (purpose == NfcKeyPurpose.ENROLL) "Hold a dedicated NFC tag against the phone to enroll it." else "Hold your enrolled NFC key against the phone.")
        adapter.enableReaderMode(
            this,
            { tag ->
                adapter.disableReaderMode(this)
                controller.onNfcKeyScanned(tag.id ?: ByteArray(0), enroll = purpose == NfcKeyPurpose.ENROLL)
            },
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            null,
        )
    }

    private fun triggerPanic(action: PanicAction, now: Long) {
        if (now - lastPanicAt < 10_000L) return
        lastPanicAt = now; shakeHits = 0; faceDownSince = 0L
        lifecycleScope.launch { controller.performPanicAction(action) }
    }

    /** Do not expose the current screen hierarchy or media metadata to Assistant/content capture. */
    override fun onProvideAssistData(data: Bundle) {
        data.clear()
    }

    override fun onProvideAssistContent(outContent: AssistContent) {
        // Intentionally empty: there is no shareable web URI or structured vault content.
    }

    private fun hardenWindow() {
        // Debug builds only: visual QA on an emulator can opt in to screenshots with
        // `adb shell run-as <package> touch files/qa_allow_screenshots`. BuildConfig.DEBUG is a
        // compile-time constant, so release builds always set FLAG_SECURE.
        allowInsecureQaScreenshots = BuildConfig.DEBUG && File(filesDir, QA_SCREENSHOT_MARKER).exists()
        if (!allowInsecureQaScreenshots) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.decorView.importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.setHideOverlayWindows(true)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setRecentsScreenshotEnabled(false)
        }
    }

    companion object {
        private const val BUSY_RECHECK_MILLIS = 60 * 1_000L
        private const val QA_SCREENSHOT_MARKER = "qa_allow_screenshots"
    }
}
