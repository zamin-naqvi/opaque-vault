package app.opaquevault

import android.app.ActivityManager
import android.content.Context
import android.content.IntentSender
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.format.Formatter
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.opaquevault.media.ImageCodec
import app.opaquevault.media.VideoFrames
import app.opaquevault.security.AfterImportAction
import app.opaquevault.security.BiometricKeyManager
import app.opaquevault.security.BiometricKeyUnavailableException
import app.opaquevault.security.CryptoEngine
import app.opaquevault.security.DeviceKeyUnavailableException
import app.opaquevault.security.DeviceRiskAssessment
import app.opaquevault.security.DisguiseMode
import app.opaquevault.security.DuressManager
import app.opaquevault.security.EncryptedIntruderLog
import app.opaquevault.security.FreeSpaceWiper
import app.opaquevault.security.IntruderEvent
import app.opaquevault.security.IntruderCamera
import app.opaquevault.security.KeystoreManager
import app.opaquevault.security.LauncherIdentity
import app.opaquevault.security.LauncherIdentityManager
import app.opaquevault.security.NfcKeyManager
import app.opaquevault.security.PanicAction
import app.opaquevault.security.SecurityPreferences
import app.opaquevault.security.ThemeMode
import app.opaquevault.security.VaultCorruptException
import app.opaquevault.security.VaultEmergencyEraser
import app.opaquevault.security.VaultKeyManager
import app.opaquevault.security.VaultSettings
import app.opaquevault.security.WrongPassphraseException
import app.opaquevault.security.wipe
import app.opaquevault.vault.ExportTarget
import app.opaquevault.vault.ImportTooLargeException
import app.opaquevault.vault.MediaExporter
import app.opaquevault.vault.ImportedOriginal
import app.opaquevault.vault.InsufficientStorageException
import app.opaquevault.vault.OriginalCleaner
import app.opaquevault.vault.UnsupportedImportTypeException
import app.opaquevault.vault.VaultBackup
import app.opaquevault.vault.VaultEntry
import app.opaquevault.vault.VaultImportResult
import app.opaquevault.vault.VaultIntegrityException
import app.opaquevault.vault.VaultMediaReader
import app.opaquevault.vault.VaultOpenLimitException
import app.opaquevault.vault.VaultRepository
import app.opaquevault.vault.isVideo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InterruptedIOException
import java.security.MessageDigest
import javax.crypto.Cipher

class VaultController(context: Context) : AutoCloseable {
    enum class Phase { SETUP, LOCKED, UNLOCKED }

    /** A long-running operation shown over the UI. [progress] null means indeterminate. */
    data class Busy(
        val label: String,
        val progress: Float? = null,
        val detail: String? = null,
        val cancellable: Boolean = false,
    )

    data class UiState(
        val phase: Phase,
        val settings: VaultSettings,
        val busy: Busy? = null,
        val entries: List<VaultEntry> = emptyList(),
        val corruptedObjectCount: Int = 0,
        val hardwareLevel: KeystoreManager.HardwareLevel = KeystoreManager.HardwareLevel.UNKNOWN,
        val kdfMemoryMiB: Int = 0,
        val message: String? = null,
        val error: String? = null,
        val environmentWarning: String? = null,
        /** Originals of the last verified import, waiting for "delete" or "keep". */
        val pendingSourceDeletion: List<ImportedOriginal> = emptyList(),
        /** Images received from a share or a late picker result, waiting for unlock + confirmation. */
        val pendingImports: List<Uri> = emptyList(),
        val failedUnlockCount: Int = 0,
        val lockoutUntilEpochMillis: Long = 0,
        val deviceAuthenticationSatisfied: Boolean = true,
        val canSearchPhotos: Boolean = false,
        val hasFullPhotoAccess: Boolean = false,
        val canDeleteWithoutConfirmation: Boolean = false,
        val nfcKeySatisfied: Boolean = true,
        val signatureStatus: String = "Not checked",
        val attestationSummary: String = "Not checked",
        val intruderEvents: List<IntruderEvent> = emptyList(),
        val intruderPhoto: Bitmap? = null,
        val duressEnabled: Boolean = false,
    )

    /** Next step of removing originals; the UI performs the parts that need an Activity. */
    sealed interface DeletionStep {
        class NeedsPhotoAccess(val permissions: Array<String>, val count: Int) : DeletionStep
        class NeedsSystemConfirmation(val intentSender: IntentSender) : DeletionStep
        data object Done : DeletionStep
    }

    sealed interface LoadedImage {
        class Ready(val bitmap: Bitmap) : LoadedImage
        class Failed(val reason: String) : LoadedImage
    }

    private sealed interface ImportOutcome {
        class Imported(val original: ImportedOriginal) : ImportOutcome
        data object NotMedia : ImportOutcome
        data object TooLarge : ImportOutcome
        data object NoSpace : ImportOutcome
        data object Stalled : ImportOutcome
        data object Failed : ImportOutcome
    }

    private class DeletionSession(
        val directlyDeleted: Int,
        val mediaStoreItems: MutableList<Uri>,
        var needsSearch: List<ImportedOriginal>,
        var notDeletable: Int,
        var missingPhotoAccess: Boolean = false,
    )

    private val appContext = context.applicationContext
    private val activityManager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val keyManager = VaultKeyManager(appContext)
    private val repository = VaultRepository(appContext)
    private val cleaner = OriginalCleaner(appContext)
    private val backup = VaultBackup(appContext, repository)
    private val exporter = MediaExporter(appContext, repository)
    private val wiper = FreeSpaceWiper(appContext)
    private val preferences = SecurityPreferences(appContext)
    private val launcherIdentityManager = LauncherIdentityManager(appContext)
    private val deviceRiskAssessment = DeviceRiskAssessment(appContext)
    private val biometricKeyManager = BiometricKeyManager(appContext)
    private val nfcKeyManager = NfcKeyManager(appContext)
    private val duressManager = DuressManager(appContext)
    private val intruderLog = EncryptedIntruderLog(appContext)
    private val intruderCamera = IntruderCamera(appContext, intruderLog)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val operationMutex = Mutex()
    private val keyMonitor = Any()
    private var masterKey: ByteArray? = null
    private var sessionGeneration: Long = 0L
    private var deletionSession: DeletionSession? = null

    @Volatile
    private var cancelRequested = false

    @OptIn(ExperimentalCoroutinesApi::class)
    private val thumbnailDispatcher = Dispatchers.IO.limitedParallelism(2)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val imageDispatcher = Dispatchers.IO.limitedParallelism(2)

    // Decrypted pixels live only in these in-memory caches and are released on lock.
    private val thumbnailCache = object : LruCache<String, Bitmap>(thumbnailCacheKiB()) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.allocationByteCount / 1024).coerceAtLeast(1)

        override fun entryRemoved(evicted: Boolean, key: String, oldValue: Bitmap, newValue: Bitmap?) {
            if (oldValue !== newValue) scheduleBitmapRecycle(oldValue)
        }
    }
    private val imageCache = object : LruCache<String, Bitmap>(3) {
        override fun entryRemoved(evicted: Boolean, key: String, oldValue: Bitmap, newValue: Bitmap?) {
            if (oldValue !== newValue) scheduleBitmapRecycle(oldValue)
        }
    }
    private val unavailableThumbnails = HashSet<String>()

    @Volatile
    var externalActivityPending: Boolean = false

    private var startupDeadManTriggered = false

    var state by mutableStateOf(initialState())
        private set

    init {
        if (preferences.biometricKeyEnabled && !biometricKeyManager.isEnrolled()) preferences.biometricKeyEnabled = false
        if (preferences.nfcKeyEnabled && !nfcKeyManager.isEnrolled()) preferences.nfcKeyEnabled = false
        // Keep launcher/share aliases in sync after an app update or launcher reset.
        launcherIdentityManager.apply(preferences.launcherIdentity)
        // A wipe interrupted by a crash must not leave storage full.
        Thread { wiper.cleanupLeftovers() }.start()
    }

    val isBusy: Boolean get() = state.busy != null

    // ---------------------------------------------------------------------------------------------
    // Vault lifecycle
    // ---------------------------------------------------------------------------------------------

    suspend fun createVault(passphrase: CharArray, confirmation: CharArray) = operationMutex.withLock {
        createVaultLocked(passphrase, confirmation)
    }

    /** First-run restore: proves the backup passphrase, makes it the vault passphrase, then restores. */
    suspend fun createVaultAndRestore(passphrase: CharArray, source: Uri) = operationMutex.withLock {
        state = state.copy(busy = Busy("Checking the backup passphrase", detail = "Deriving the key with Argon2id"), error = null, message = null)
        try {
            withContext(Dispatchers.IO) { backup.verifyPassphrase(passphrase, source) }
        } catch (error: CancellationException) {
            passphrase.wipe()
            throw error
        } catch (error: Exception) {
            passphrase.wipe()
            state = state.copy(busy = null, error = describeRestoreFailure(error))
            return@withLock
        }
        createVaultLocked(passphrase.copyOf(), passphrase.copyOf())
        if (state.phase == Phase.UNLOCKED) restoreLocked(source, passphrase) else passphrase.wipe()
    }

    private suspend fun createVaultLocked(passphrase: CharArray, confirmation: CharArray) {
        state = state.copy(busy = Busy("Creating your vault", detail = "Hardening the passphrase with Argon2id"), error = null, message = null)
        val operationGeneration = currentGeneration()
        try {
            val risk = deviceRiskAssessment.snapshot(preferences.strictDeviceIntegrity, preferences.signaturePinningEnabled)
            state = state.copy(environmentWarning = risk.warning)
            if (risk.blockUnlock) {
                state = state.copy(busy = null, error = risk.warning)
                return
            }
            validateNewPassphrase(passphrase, confirmation)
            val kdf = vaultKdfParameters()
            val unlocked = withContext(Dispatchers.IO) { keyManager.createVault(passphrase, kdf) }
            if (!installMasterKeyIfCurrent(unlocked.masterKey, operationGeneration)) {
                state = state.copy(phase = Phase.LOCKED, busy = null, entries = emptyList())
                return
            }
            preferences.clearFailedUnlocks()
            preferences.lastSuccessfulUnlockEpochMillis = System.currentTimeMillis()
            val listing = withMasterKeyCopy { key -> withContext(Dispatchers.IO) { repository.listEntries(key) } }
            if (!isGenerationCurrent(operationGeneration)) return
            state = state.copy(
                phase = Phase.UNLOCKED,
                busy = null,
                entries = listing.entries,
                corruptedObjectCount = listing.corruptedObjectCount,
                hardwareLevel = unlocked.hardwareLevel,
                kdfMemoryMiB = unlocked.kdfParameters.memoryKiB / 1024,
                message = "Vault created. Nobody, including the developer, can recover a lost passphrase.",
                error = null,
                failedUnlockCount = 0,
                lockoutUntilEpochMillis = 0,
                deviceAuthenticationSatisfied = true,
            )
            refreshPermissions()
        } catch (error: CancellationException) {
            throw error
        } catch (error: IllegalArgumentException) {
            state = state.copy(busy = null, error = error.message ?: "Passphrase rejected")
        } catch (_: Exception) {
            clearSessionKey()
            state = state.copy(
                phase = if (keyManager.isInitialized()) Phase.LOCKED else Phase.SETUP,
                busy = null,
                error = "Vault creation failed safely. No media was stored.",
            )
        } finally {
            passphrase.wipe()
            confirmation.wipe()
        }
    }

    suspend fun unlock(passphrase: CharArray) = operationMutex.withLock {
        val operationGeneration = currentGeneration()
        try {
            val risk = deviceRiskAssessment.snapshot(preferences.strictDeviceIntegrity, preferences.signaturePinningEnabled)
            state = state.copy(environmentWarning = risk.warning)
            if (risk.blockUnlock) {
                state = state.copy(busy = null, error = risk.warning)
                return@withLock
            }
            if (preferences.nfcKeyEnabled && !state.nfcKeySatisfied) {
                state = state.copy(error = "Scan your enrolled NFC key before entering the passphrase.")
                return@withLock
            }
            val now = System.currentTimeMillis()
            if (preferences.lockoutUntilEpochMillis > now) {
                val seconds = ((preferences.lockoutUntilEpochMillis - now + 999) / 1_000).coerceAtLeast(1)
                state = state.copy(error = "Too many attempts. Try again in $seconds seconds.")
                return@withLock
            }
            state = state.copy(busy = Busy("Unlocking", detail = "Deriving keys with Argon2id"), error = null, message = null)
            val unlocked = withContext(Dispatchers.IO) { keyManager.unlock(passphrase) }
            if (!installMasterKeyIfCurrent(unlocked.masterKey, operationGeneration)) {
                state = state.copy(phase = Phase.LOCKED, busy = null, entries = emptyList())
                return@withLock
            }
            preferences.clearFailedUnlocks()
            preferences.lastSuccessfulUnlockEpochMillis = System.currentTimeMillis()
            val listing = withMasterKeyCopy { key -> withContext(Dispatchers.IO) { repository.listEntries(key) } }
            if (!isGenerationCurrent(operationGeneration)) return@withLock
            state = state.copy(
                phase = Phase.UNLOCKED,
                busy = null,
                entries = listing.entries,
                corruptedObjectCount = listing.corruptedObjectCount,
                hardwareLevel = unlocked.hardwareLevel,
                kdfMemoryMiB = unlocked.kdfParameters.memoryKiB / 1024,
                failedUnlockCount = 0,
                lockoutUntilEpochMillis = 0,
                error = null,
                deviceAuthenticationSatisfied = true,
            )
            refreshPermissions()
        } catch (error: CancellationException) {
            throw error
        } catch (_: WrongPassphraseException) {
            clearSessionKey()
            if (duressManager.isEnabled() && withContext(Dispatchers.IO) { duressManager.matches(passphrase) }) {
                withContext(Dispatchers.IO) { destroyAllSecurityMaterial() }
                state = initialState().copy(error = "Unable to unlock this vault.")
                return@withLock
            }
            if (preferences.intruderLogEnabled) {
                withContext(Dispatchers.IO) { runCatching { intruderLog.append("Incorrect passphrase") } }
                if (preferences.captureIntruderPhoto) intruderCamera.captureIfPermitted()
            }
            val eraseThreshold = preferences.wipeFailureThreshold
            val failed = preferences.recordFailedUnlock(System.currentTimeMillis())
            if (failed.mustWipe) {
                withContext(Dispatchers.IO) { destroyAllSecurityMaterial() }
                launcherIdentityManager.apply(LauncherIdentity.VAULT)
                state = initialState().copy(message = "Vault erased after $eraseThreshold wrong passphrases.")
            } else {
                state = state.copy(
                    phase = Phase.LOCKED,
                    busy = null,
                    entries = emptyList(),
                    failedUnlockCount = failed.failures,
                    lockoutUntilEpochMillis = failed.lockoutUntilEpochMillis,
                    error = "Incorrect passphrase.",
                )
            }
        } catch (_: DeviceKeyUnavailableException) {
            clearSessionKey()
            state = state.copy(
                phase = Phase.LOCKED,
                busy = null,
                error = "This vault's hardware key is unavailable on this phone, so it can't be decrypted here.",
            )
        } catch (_: VaultCorruptException) {
            clearSessionKey()
            state = state.copy(
                phase = Phase.LOCKED,
                busy = null,
                error = "The vault header is damaged or was modified, so it was refused.",
            )
        } catch (_: Exception) {
            clearSessionKey()
            state = state.copy(phase = Phase.LOCKED, busy = null, error = "Unlock failed safely.")
        } finally {
            passphrase.wipe()
        }
    }

    fun lock() {
        clearSessionKey()
        releaseDecryptedImages()
        state.pendingSourceDeletion.forEach { it.sha256.wipe() }
        deletionSession?.needsSearch?.forEach { it.sha256.wipe() }
        deletionSession = null
        state.intruderPhoto?.let { if (!it.isRecycled) it.recycle() }
        state = state.copy(
            phase = if (keyManager.isInitialized()) Phase.LOCKED else Phase.SETUP,
            busy = null,
            entries = emptyList(),
            intruderEvents = emptyList(),
            intruderPhoto = null,
            corruptedObjectCount = 0,
            pendingSourceDeletion = emptyList(),
            error = null,
            message = null,
            deviceAuthenticationSatisfied = true,
            nfcKeySatisfied = !preferences.nfcKeyEnabled,
        )
    }

    suspend fun cryptoErase() = operationMutex.withLock {
        clearSessionKey()
        releaseDecryptedImages()
        state.pendingSourceDeletion.forEach { it.sha256.wipe() }
        deletionSession?.needsSearch?.forEach { it.sha256.wipe() }
        deletionSession = null
        state = state.copy(busy = Busy("Destroying the vault key"))
        withContext(Dispatchers.IO) { destroyAllSecurityMaterial() }
        launcherIdentityManager.apply(LauncherIdentity.VAULT)
        state = initialState().copy(message = "Vault destroyed. Its encrypted data can no longer be decrypted.")
    }

    suspend fun changePassphrase(
        currentPassphrase: CharArray,
        newPassphrase: CharArray,
        confirmation: CharArray,
    ) = operationMutex.withLock {
        val operationGeneration = currentGeneration()
        state = state.copy(
            busy = Busy("Changing passphrase", detail = "Verifying the current passphrase with Argon2id"),
            error = null,
            message = null,
        )
        try {
            validateNewPassphrase(newPassphrase, confirmation)
            val verified = withContext(Dispatchers.IO) { keyManager.unlock(currentPassphrase) }
            val sameVault = try {
                withMasterKeyCopy { MessageDigest.isEqual(it, verified.masterKey) }
            } finally {
                verified.masterKey.wipe()
            }
            if (!sameVault) throw WrongPassphraseException()
            val kdf = vaultKdfParameters()
            state = state.copy(busy = Busy("Changing passphrase", detail = "Re-wrapping the master key"))
            withMasterKeyCopy { key ->
                withContext(Dispatchers.IO) { keyManager.changePassphrase(key, newPassphrase, kdf) }
            }
            if (!isGenerationCurrent(operationGeneration) || state.phase != Phase.UNLOCKED) return@withLock
            state = state.copy(
                busy = null,
                kdfMemoryMiB = kdf.memoryKiB / 1024,
                message = "Passphrase changed. Encrypted items didn't need re-encrypting.",
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: WrongPassphraseException) {
            state = state.copy(busy = null, error = "The current passphrase is incorrect. Nothing was changed.")
        } catch (error: IllegalArgumentException) {
            state = state.copy(busy = null, error = error.message ?: "Passphrase rejected")
        } catch (_: Exception) {
            state = state.copy(busy = null, error = "Passphrase change failed; the old passphrase still works.")
        } finally {
            currentPassphrase.wipe()
            newPassphrase.wipe()
            confirmation.wipe()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Importing
    // ---------------------------------------------------------------------------------------------

    /**
     * Encrypts the given images. If the vault is locked (for example it auto-locked while the
     * Photo Picker was open), the images are queued and offered again after unlock.
     */
    suspend fun importDocuments(uris: List<Uri>) = operationMutex.withLock {
        if (uris.isEmpty()) return@withLock
        if (state.phase != Phase.UNLOCKED) {
            queueImports(uris)
            return@withLock
        }
        val operationGeneration = currentGeneration()
        cancelRequested = false
        val unique = uris.distinct()
        val batch = unique.take(MAX_BATCH_IMPORTS)
        val overLimit = unique.size - batch.size
        val key = copyMasterKeyOrNull()
        if (key == null) {
            queueImports(batch)
            return@withLock
        }
        try {
            val imported = mutableListOf<ImportedOriginal>()
            var failedCount = 0
            var unsupportedCount = 0
            var tooLargeCount = 0
            var noSpaceCount = 0
            var stalledCount = 0
            var processed = 0
            for ((index, uri) in batch.withIndex()) {
                if (!isGenerationCurrent(operationGeneration) || cancelRequested) break
                state = state.copy(
                    busy = Busy(
                        label = "Encrypting photos and videos",
                        progress = index.toFloat() / batch.size,
                        detail = "${index + 1} of ${batch.size} · each copy is authenticated",
                        cancellable = true,
                    ),
                    error = null,
                    message = null,
                )
                var lastPostedAt = 0L
                var lastPostedPermille = -1
                // Updates are coalesced (at most ~4 per second, only when the bar moves) so the
                // progress card doesn't repaint on every 4 MB chunk and flicker.
                val reportProgress = { phase: String, done: Long, total: Long ->
                    val now = SystemClock.elapsedRealtime()
                    val itemFraction = if (total > 0L) (done.toDouble() / total).coerceIn(0.0, 1.0) else 0.0
                    val overall = ((index + itemFraction) / batch.size).coerceIn(0.0, 1.0)
                    val permille = (overall * 1000).toInt()
                    if (now - lastPostedAt >= PROGRESS_MIN_INTERVAL_MILLIS && permille != lastPostedPermille) {
                        lastPostedAt = now
                        lastPostedPermille = permille
                        postBusy(operationGeneration) { busyState ->
                            busyState.copy(
                                progress = overall.toFloat(),
                                detail = "$phase ${index + 1} of ${batch.size} · ${bytes(done)}" +
                                    if (total > 0L) " of ${bytes(total)}" else "",
                            )
                        }
                    }
                }
                val outcome = withContext(Dispatchers.IO) {
                    try {
                        val result = repository.importUri(
                            masterKey = key,
                            sourceUri = uri,
                            durationMillis = 0L,
                            shouldContinue = {
                                isGenerationCurrent(operationGeneration) && !cancelRequested
                            },
                            onProgress = { done, total -> reportProgress("Encrypting", done, total) },
                            onVerifyProgress = { done, total -> reportProgress("Verifying", done, total) },
                        )
                        // Post-processing is isolated from the committed import. In particular,
                        // videos are probed from ciphertext so one-shot OEM share URIs are opened once.
                        storeImportPreview(key, result, uri)
                        ImportOutcome.Imported(result.original)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: UnsupportedImportTypeException) {
                        ImportOutcome.NotMedia
                    } catch (_: ImportTooLargeException) {
                        ImportOutcome.TooLarge
                    } catch (_: InsufficientStorageException) {
                        ImportOutcome.NoSpace
                    } catch (_: app.opaquevault.vault.SourceStalledException) {
                        ImportOutcome.Stalled
                    } catch (_: Exception) {
                        ImportOutcome.Failed
                    } catch (_: OutOfMemoryError) {
                        ImportOutcome.TooLarge
                    }
                }
                if (!isGenerationCurrent(operationGeneration)) break
                if (cancelRequested && outcome is ImportOutcome.Failed) break // the stopped item was rolled back
                when (outcome) {
                    is ImportOutcome.Imported -> imported += outcome.original
                    ImportOutcome.NotMedia -> unsupportedCount++
                    ImportOutcome.TooLarge -> tooLargeCount++
                    ImportOutcome.NoSpace -> noSpaceCount++
                    ImportOutcome.Stalled -> stalledCount++
                    ImportOutcome.Failed -> failedCount++
                }
                processed++
                if (outcome is ImportOutcome.NoSpace) break
            }
            if (!isGenerationCurrent(operationGeneration) || state.phase != Phase.UNLOCKED) {
                // Locked mid-batch: the interrupted item was rolled back, so offer the rest after unlock.
                queueImports(batch.drop(processed))
                return@withLock
            }
            val stopped = processed < batch.size
            val listing = withContext(Dispatchers.IO) { repository.listEntries(key) }
            if (!isGenerationCurrent(operationGeneration) || state.phase != Phase.UNLOCKED) return@withLock
            val problems = buildList {
                if (failedCount > 0) add("$failedCount couldn't be read or verified")
                if (unsupportedCount > 0) add("$unsupportedCount unsupported or non-media item${plural(unsupportedCount)}")
                if (tooLargeCount > 0) add("$tooLargeCount over the 64 GB per-item safety limit")
                if (noSpaceCount > 0) add("not enough free storage to finish the batch")
                if (stalledCount > 0) {
                    add("$stalledCount stopped arriving from Gallery (Samsung paused it); share ${if (stalledCount == 1) "it" else "them"} again or use Add media → Photo Picker")
                }
                if (overLimit > 0) add("$overLimit over the $MAX_BATCH_IMPORTS-item batch limit")
            }
            val keepOriginals = preferences.afterImport == AfterImportAction.KEEP
            val pendingOriginals = if (keepOriginals) {
                imported.forEach { it.sha256.wipe() }
                state.pendingSourceDeletion
            } else {
                state.pendingSourceDeletion + imported
            }
            state = state.copy(
                busy = null,
                entries = listing.entries,
                corruptedObjectCount = listing.corruptedObjectCount,
                pendingSourceDeletion = pendingOriginals,
                message = when {
                    imported.isEmpty() -> null
                    stopped -> "Stopped. ${imported.size} item${plural(imported.size)} encrypted before the operation ended."
                    keepOriginals -> "${imported.size} item${plural(imported.size)} encrypted. Originals kept (change in Settings)."
                    else -> "${imported.size} item${plural(imported.size)} encrypted and verified."
                },
                error = if (problems.isNotEmpty()) "Skipped: ${problems.joinToString("; ")}. Those originals weren't touched." else null,
            )
        } finally {
            key.wipe()
            cancelRequested = false
        }
    }

    /** Holds images from a share or a late picker result until the user unlocks and confirms. */
    fun queueImports(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val merged = LinkedHashSet(state.pendingImports).apply { addAll(uris) }.toList()
        val kept = merged.take(MAX_BATCH_IMPORTS)
        state = state.copy(
            pendingImports = kept,
            error = if (merged.size > kept.size) {
                "Only $MAX_BATCH_IMPORTS items can be imported per batch; ${merged.size - kept.size} were ignored."
            } else state.error,
        )
    }

    suspend fun importPendingImports() {
        val uris = state.pendingImports
        if (uris.isEmpty()) return
        state = state.copy(pendingImports = emptyList())
        importDocuments(uris)
    }

    fun discardPendingImports() {
        state = state.copy(pendingImports = emptyList(), message = "Nothing was imported.")
    }

    private fun storeImportPreview(key: ByteArray, result: VaultImportResult, uri: Uri) {
        // Videos get their frame and duration lazily from the encrypted copy (see loadThumbnail), so a
        // slow or odd media decoder can never stall, or hold a lock on, the import itself.
        if (result.entry.isVideo()) return

        var generatedJpeg: ByteArray? = null
        try {
            val jpeg = ImageCodec.thumbnailFromUri(
                appContext.contentResolver,
                uri,
                VaultRepository.DEFAULT_OPEN_LIMIT,
            ).also { generatedJpeg = it }
            if (jpeg.isNotEmpty()) repository.storeThumbnail(key, result.entry.id, jpeg)
        } catch (_: Exception) {
            // Generated later from the authenticated encrypted copy when first displayed.
        } catch (_: OutOfMemoryError) {
            // Same fallback.
        } finally {
            generatedJpeg?.wipe()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Removing originals
    // ---------------------------------------------------------------------------------------------

    suspend fun beginOriginalDeletion(): DeletionStep = operationMutex.withLock {
        val pending = state.pendingSourceDeletion
        if (pending.isEmpty()) return@withLock DeletionStep.Done
        state = state.copy(pendingSourceDeletion = emptyList(), busy = Busy("Removing originals"), error = null, message = null)
        val classification = withContext(Dispatchers.IO) { cleaner.classify(pending) }
        val retainedForHashSearch = classification.needsSearch.toSet()
        pending.filterNot { it in retainedForHashSearch }.forEach { it.sha256.wipe() }
        val session = DeletionSession(
            directlyDeleted = classification.directlyDeleted,
            mediaStoreItems = classification.mediaStoreItems.toMutableList(),
            needsSearch = classification.needsSearch,
            notDeletable = classification.unsupported,
        )
        deletionSession = session
        if (session.needsSearch.isNotEmpty() && cleaner.supportsSystemDeletion() && !cleaner.canSearchMediaStore()) {
            state = state.copy(busy = null)
            return@withLock DeletionStep.NeedsPhotoAccess(cleaner.mediaReadPermissions(), session.needsSearch.size)
        }
        proceedWithDeletion(session)
    }

    /** Called after the photo-access prompt, whatever the user chose. */
    suspend fun continueOriginalDeletion(): DeletionStep = operationMutex.withLock {
        refreshPermissions()
        val session = deletionSession ?: return@withLock DeletionStep.Done
        proceedWithDeletion(session)
    }

    suspend fun onSystemDeletionResult(approved: Boolean) {
        val session = deletionSession ?: return
        finishDeletion(session, approved)
    }

    fun keepPendingOriginals(remember: Boolean) {
        state.pendingSourceDeletion.forEach { it.sha256.wipe() }
        if (remember) {
            preferences.afterImport = AfterImportAction.KEEP
            refreshSettings()
        }
        state = state.copy(
            pendingSourceDeletion = emptyList(),
            message = if (remember) "Originals will always be kept. Change this in Settings." else "Originals kept.",
        )
    }

    private suspend fun proceedWithDeletion(session: DeletionSession): DeletionStep {
        if (session.needsSearch.isNotEmpty()) {
            if (cleaner.supportsSystemDeletion() && cleaner.canSearchMediaStore()) {
                val generation = currentGeneration()
                state = state.copy(busy = Busy("Finding the originals", progress = 0f, detail = "Matching each item by its exact content"))
                val result = withContext(Dispatchers.IO) {
                    cleaner.searchMediaStore(
                        session.needsSearch,
                        onProgress = { done, total ->
                            postBusy(generation) { it.copy(progress = done.toFloat() / total, detail = "Checking ${done + 1} of $total") }
                        },
                        shouldContinue = { true },
                    )
                }
                session.mediaStoreItems += result.found
                session.notDeletable += result.notFound
            } else {
                session.notDeletable += session.needsSearch.size
                session.missingPhotoAccess = cleaner.supportsSystemDeletion()
            }
            session.needsSearch.forEach { it.sha256.wipe() }
            session.needsSearch = emptyList()
        }
        if (session.mediaStoreItems.isEmpty()) {
            finishDeletion(session, approved = false)
            return DeletionStep.Done
        }
        val sender = withContext(Dispatchers.IO) { cleaner.createDeleteRequest(session.mediaStoreItems) }
        if (sender == null) {
            session.notDeletable += session.mediaStoreItems.size
            session.mediaStoreItems.clear()
            finishDeletion(session, approved = false)
            return DeletionStep.Done
        }
        state = state.copy(busy = null)
        return DeletionStep.NeedsSystemConfirmation(sender)
    }

    private suspend fun finishDeletion(session: DeletionSession, approved: Boolean) {
        session.needsSearch.forEach { it.sha256.wipe() }
        session.needsSearch = emptyList()
        deletionSession = null
        val requested = session.mediaStoreItems.size
        // Confirm what actually disappeared instead of trusting the result code.
        val stillPresent = if (approved && requested > 0) {
            withContext(Dispatchers.IO) { cleaner.countStillPresent(session.mediaStoreItems) } ?: 0
        } else 0
        val removed = session.directlyDeleted + if (approved) requested - stillPresent else 0
        val declined = if (approved || requested == 0) 0 else requested
        val notDeletable = session.notDeletable + stillPresent
        val notes = buildList {
            if (declined > 0) add("Kept $declined original${plural(declined)} because Android's delete prompt was declined.")
            if (notDeletable > 0) {
                add(
                    if (session.missingPhotoAccess) {
                        "$notDeletable original${plural(notDeletable)} can't be removed without media access. " +
                            "Allow it in Settings → Photo and video access, or delete ${if (notDeletable == 1) "it" else "them"} in Gallery."
                    } else {
                        "$notDeletable original${plural(notDeletable)} couldn't be matched on this phone. " +
                            "If ${if (notDeletable == 1) "it's" else "they're"} still in Gallery, delete ${if (notDeletable == 1) "it" else "them"} there."
                    },
                )
            }
        }
        state = state.copy(
            busy = null,
            message = if (removed > 0) "Removed $removed original${plural(removed)} from your phone." else null,
            error = notes.takeIf { it.isNotEmpty() }?.joinToString(" "),
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Viewing
    // ---------------------------------------------------------------------------------------------

    fun cachedThumbnail(id: String): Bitmap? = thumbnailCache.get(id)

    suspend fun loadThumbnail(id: String): Bitmap? {
        thumbnailCache.get(id)?.let { return it }
        if (id in unavailableThumbnails) return null
        val generation = currentGeneration()
        val video = state.entries.firstOrNull { it.id == id }?.isVideo() == true
        val key = copyMasterKeyOrNull() ?: return null
        return try {
            val bitmap = withContext(thumbnailDispatcher) {
                val jpeg = repository.readThumbnail(key, id) ?: createThumbnailFromVault(key, id, video)
                try {
                    ImageCodec.decodeThumbnail(jpeg)
                } finally {
                    jpeg.wipe()
                }
            }
            if (!isGenerationCurrent(generation)) {
                if (!bitmap.isRecycled) bitmap.recycle()
                return null
            }
            thumbnailCache.put(id, bitmap)
            bitmap
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            unavailableThumbnails += id
            null
        } catch (_: OutOfMemoryError) {
            null
        } finally {
            key.wipe()
        }
    }

    /**
     * Runs the platform's video decoder on a daemon thread with a hard deadline. MediaMetadataRetriever
     * can block forever on unusual files; on timeout the reader is closed, which fails its pending
     * read, and the caller falls back to a placeholder instead of hanging.
     */
    private fun probeVideoBounded(key: ByteArray, id: String): VideoFrames.Probe {
        val reader = repository.openReader(key, id)
        val task = java.util.concurrent.FutureTask { VideoFrames.probe(reader) }
        val worker = Thread(task, "video-probe").apply { isDaemon = true }
        worker.start()
        return try {
            task.get(VIDEO_PROBE_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
        } catch (error: java.util.concurrent.TimeoutException) {
            task.cancel(true)
            throw IOException("Video preview timed out", error)
        } catch (error: java.util.concurrent.ExecutionException) {
            throw IOException("Video preview failed", error.cause ?: error)
        } finally {
            try {
                reader.close()
            } catch (_: Exception) {
                // Already closed.
            }
        }
    }

    private fun createThumbnailFromVault(key: ByteArray, id: String, video: Boolean): ByteArray {
        if (video) {
            val probe = probeVideoBounded(key, id)
            val jpeg = probe.thumbnailJpeg ?: throw IOException("No video frame is available")
            return try {
                repository.storeThumbnail(key, id, jpeg)
                if (probe.durationMillis > 0L) {
                    val updated = repository.updateDuration(key, id, probe.durationMillis)
                    mainHandler.post {
                        state = state.copy(entries = state.entries.map { if (it.id == id) updated else it })
                    }
                }
                jpeg
            } catch (error: Throwable) {
                jpeg.wipe()
                throw error
            }
        }
        val (_, content) = repository.openInMemory(key, id)
        return try {
            ImageCodec.thumbnailFromBytes(content).also { repository.storeThumbnail(key, id, it) }
        } finally {
            content.wipe()
        }
    }

    suspend fun loadImage(id: String): LoadedImage {
        imageCache.get(id)?.let { return LoadedImage.Ready(it) }
        val generation = currentGeneration()
        val key = copyMasterKeyOrNull() ?: return LoadedImage.Failed("The vault is locked.")
        return try {
            val bitmap = withContext(imageDispatcher) {
                val (_, content) = repository.openInMemory(key, id)
                try {
                    ImageCodec.decodeForViewer(content)
                } finally {
                    content.wipe()
                }
            }
            if (!isGenerationCurrent(generation)) {
                if (!bitmap.isRecycled) bitmap.recycle()
                return LoadedImage.Failed("The vault is locked.")
            }
            imageCache.put(id, bitmap)
            LoadedImage.Ready(bitmap)
        } catch (error: CancellationException) {
            throw error
        } catch (_: VaultIntegrityException) {
            LoadedImage.Failed("This photo failed its integrity check, so it wasn't shown.")
        } catch (_: VaultOpenLimitException) {
            LoadedImage.Failed("This photo is too large to open safely.")
        } catch (_: Exception) {
            LoadedImage.Failed("This photo couldn't be decoded.")
        } catch (_: OutOfMemoryError) {
            LoadedImage.Failed("Not enough memory to open this photo.")
        } finally {
            key.wipe()
        }
    }

    /**
     * Opens a generation-bound random-access stream for Media3. The wrapper checks the vault session
     * under the same monitor used by [lock], so no plaintext read can begin after locking completes.
     */
    fun openVaultReader(id: String): VaultMediaReader {
        val session = synchronized(keyMonitor) {
            val key = masterKey?.copyOf() ?: throw IOException("The vault is locked")
            sessionGeneration to key
        }
        val generation = session.first
        val key = session.second
        val delegate = try {
            repository.openReader(key, id)
        } finally {
            key.wipe()
        }
        if (!isGenerationCurrent(generation)) {
            delegate.close()
            throw IOException("The vault was locked")
        }
        return object : VaultMediaReader {
            override val size: Long = delegate.size
            private var closed = false

            @Synchronized
            override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
                if (closed) throw IOException("The media stream is closed")
                return synchronized(keyMonitor) {
                    if (sessionGeneration != generation || masterKey == null) {
                        throw IOException("The vault is locked")
                    }
                    delegate.read(position, buffer, offset, length)
                }
            }

            @Synchronized
            override fun close() {
                if (!closed) {
                    closed = true
                    delegate.close()
                }
            }
        }
    }

    suspend fun deleteEntries(ids: Collection<String>) = operationMutex.withLock {
        if (ids.isEmpty() || state.phase != Phase.UNLOCKED) return@withLock
        val operationGeneration = currentGeneration()
        state = state.copy(busy = Busy("Removing ${ids.size} item${plural(ids.size)}", detail = "Destroying each file key first"))
        try {
            val listing = withMasterKeyCopy { key ->
                withContext(Dispatchers.IO) {
                    ids.forEach { repository.deleteEntry(key, it) }
                    repository.listEntries(key)
                }
            }
            ids.forEach {
                thumbnailCache.remove(it)
                imageCache.remove(it)
            }
            if (!isGenerationCurrent(operationGeneration) || state.phase != Phase.UNLOCKED) return@withLock
            state = state.copy(
                busy = null,
                entries = listing.entries,
                corruptedObjectCount = listing.corruptedObjectCount,
                message = "Removed ${ids.size} item${plural(ids.size)} from the vault.",
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            state = state.copy(busy = null, error = "Couldn't remove the selected items.")
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Backup, restore and storage wipe
    // ---------------------------------------------------------------------------------------------

    /**
     * Decrypts the chosen items back to ordinary files. The passphrase is required again, so a person
     * who finds the vault unlocked cannot copy everything out of it.
     */
    suspend fun exportMedia(
        ids: List<String>,
        target: ExportTarget,
        removeAfterExport: Boolean,
        stripSensitiveMetadata: Boolean,
        passphrase: CharArray,
    ) = operationMutex.withLock {
        if (state.phase != Phase.UNLOCKED || ids.isEmpty()) {
            passphrase.wipe()
            return@withLock
        }
        val operationGeneration = currentGeneration()
        cancelRequested = false
        try {
            state = state.copy(busy = Busy("Confirming your passphrase", detail = "Deriving keys with Argon2id"), error = null, message = null)
            val verified = withContext(Dispatchers.IO) { keyManager.unlock(passphrase) }
            val sameVault = try {
                withMasterKeyCopy { MessageDigest.isEqual(it, verified.masterKey) }
            } finally {
                verified.masterKey.wipe()
            }
            if (!sameVault) throw WrongPassphraseException()

            val wanted = ids.toSet()
            val items = state.entries.filter { it.id in wanted }
            val totalBytes = items.sumOf { it.plaintextSize }.coerceAtLeast(1L)
            var finishedBytes = 0L
            var exported = 0
            var failed = 0
            var removed = 0
            withMasterKeyCopy { key ->
                for ((index, entry) in items.withIndex()) {
                    if (!isGenerationCurrent(operationGeneration) || cancelRequested) break
                    state = state.copy(
                        busy = Busy(
                            label = "Exporting",
                            progress = finishedBytes.toFloat() / totalBytes,
                            detail = "${index + 1} of ${items.size} · ${entry.displayName}",
                            cancellable = true,
                        ),
                    )
                    var lastPost = 0L
                    val ok = withContext(Dispatchers.IO) {
                        try {
                            exporter.export(
                                masterKey = key,
                                entry = entry,
                                target = target,
                                shouldContinue = { isGenerationCurrent(operationGeneration) && !cancelRequested },
                                onBytes = { written ->
                                    val now = SystemClock.elapsedRealtime()
                                    if (now - lastPost >= PROGRESS_MIN_INTERVAL_MILLIS) {
                                        lastPost = now
                                        postBusy(operationGeneration) {
                                            it.copy(progress = ((finishedBytes + written).toFloat() / totalBytes).coerceIn(0f, 1f))
                                        }
                                    }
                                },
                                stripSensitiveMetadata = stripSensitiveMetadata,
                            )
                            if (removeAfterExport) repository.deleteEntry(key, entry.id)
                            true
                        } catch (error: InterruptedIOException) {
                            throw error
                        } catch (_: Exception) {
                            false
                        }
                    }
                    finishedBytes += entry.plaintextSize
                    if (ok) {
                        exported++
                        if (removeAfterExport) {
                            removed++
                            thumbnailCache.remove(entry.id)
                            imageCache.remove(entry.id)
                        }
                    } else {
                        failed++
                    }
                }
            }
            refreshEntries(operationGeneration)
            if (isGenerationCurrent(operationGeneration)) {
                val where = when (target) {
                    ExportTarget.Gallery -> "Gallery (Pictures and Movies › ${MediaExporter.EXPORT_FOLDER})"
                    is ExportTarget.Folder -> "the folder you chose"
                }
                state = state.copy(
                    busy = null,
                    message = buildString {
                        append(if (cancelRequested) "Stopped. " else "")
                        append("Exported $exported item${plural(exported)} to $where.")
                        if (removed > 0) append(" $removed removed from the vault.")
                    },
                    error = if (failed > 0) "$failed item${plural(failed)} couldn't be exported and stayed in the vault." else null,
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            refreshEntries(operationGeneration)
            if (isGenerationCurrent(operationGeneration)) {
                state = state.copy(
                    busy = null,
                    message = if (error is InterruptedIOException) "Export stopped. The unfinished copy was removed." else null,
                    error = when (error) {
                        is InterruptedIOException -> null
                        is WrongPassphraseException -> "Incorrect passphrase. Nothing was exported."
                        else -> "Export failed. Nothing unverified was left behind."
                    },
                )
            }
        } finally {
            passphrase.wipe()
            cancelRequested = false
        }
    }

    /** Re-authenticates with [passphrase], then writes every item into one encrypted file. */
    suspend fun exportBackup(destination: Uri, passphrase: CharArray) = operationMutex.withLock {
        if (state.phase != Phase.UNLOCKED) {
            passphrase.wipe()
            return@withLock
        }
        val operationGeneration = currentGeneration()
        cancelRequested = false
        var succeeded = false
        try {
            state = state.copy(busy = Busy("Confirming your passphrase", detail = "Deriving keys with Argon2id"), error = null, message = null)
            val verified = withContext(Dispatchers.IO) { keyManager.unlock(passphrase) }
            val sameVault = try {
                withMasterKeyCopy { MessageDigest.isEqual(it, verified.masterKey) }
            } finally {
                verified.masterKey.wipe()
            }
            if (!sameVault) throw WrongPassphraseException()

            val kdf = backupKdfParameters()
            val entries = state.entries
            state = state.copy(
                busy = Busy(
                    label = "Creating encrypted backup",
                    progress = 0f,
                    detail = "Hardening the backup key with Argon2id (${kdf.memoryKiB / 1024} MB)",
                    cancellable = true,
                ),
            )
            val result = withMasterKeyCopy { key ->
                withContext(Dispatchers.IO) {
                    backup.export(
                        masterKey = key,
                        passphrase = passphrase,
                        destination = destination,
                        kdf = kdf,
                        entries = entries,
                        onProgress = { done, total ->
                            postBusy(operationGeneration) {
                                it.copy(progress = if (total == 0) 1f else done.toFloat() / total, detail = "Encrypting $done of $total")
                            }
                        },
                        shouldContinue = { isGenerationCurrent(operationGeneration) && !cancelRequested },
                    )
                }
            }
            succeeded = true
            if (isGenerationCurrent(operationGeneration)) {
                state = state.copy(
                    busy = null,
                    message = "Backup saved: ${result.exported} item${plural(result.exported)} in one encrypted file." +
                        if (result.skipped > 0) " ${result.skipped} damaged item${plural(result.skipped)} skipped." else "",
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (isGenerationCurrent(operationGeneration)) {
                state = state.copy(
                    busy = null,
                    message = if (error is InterruptedIOException) "Backup cancelled. The partial file was removed." else null,
                    error = when (error) {
                        is InterruptedIOException -> null
                        is WrongPassphraseException -> "Incorrect passphrase. No backup was created."
                        else -> "Backup failed, so the partial file was removed."
                    },
                )
            }
        } catch (_: OutOfMemoryError) {
            if (isGenerationCurrent(operationGeneration)) {
                state = state.copy(busy = null, error = "Not enough memory to create the backup.")
            }
        } finally {
            passphrase.wipe()
            cancelRequested = false
            if (!succeeded) withContext(NonCancellable + Dispatchers.IO) { backup.discardPartial(destination) }
        }
    }

    /** The user created a backup file but cancelled at the passphrase prompt: remove the empty file. */
    fun discardUnusedBackupFile(destination: Uri) {
        Thread { backup.discardPartial(destination) }.start()
    }

    suspend fun restoreBackup(source: Uri, passphrase: CharArray) = operationMutex.withLock {
        restoreLocked(source, passphrase)
    }

    private suspend fun restoreLocked(source: Uri, passphrase: CharArray) {
        if (state.phase != Phase.UNLOCKED) {
            passphrase.wipe()
            return
        }
        val operationGeneration = currentGeneration()
        cancelRequested = false
        try {
            state = state.copy(
                busy = Busy("Restoring backup", detail = "Deriving the backup key with Argon2id", cancellable = true),
                error = null,
                message = null,
            )
            val result = withMasterKeyCopy { key ->
                withContext(Dispatchers.IO) {
                    backup.restore(
                        masterKey = key,
                        passphrase = passphrase,
                        source = source,
                        onRestored = { restored ->
                            // Restore does not need source-deletion metadata; erase its digest copy now.
                            restored.original.sha256.wipe()
                        },
                        onProgress = { count -> postBusy(operationGeneration) { it.copy(detail = "Restored $count item${plural(count)}") } },
                        shouldContinue = { isGenerationCurrent(operationGeneration) && !cancelRequested },
                    )
                }
            }
            refreshEntries(operationGeneration)
            if (isGenerationCurrent(operationGeneration)) {
                state = state.copy(
                    busy = null,
                    message = buildString {
                        append("Restored ${result.restored} item${plural(result.restored)}.")
                        if (result.duplicates > 0) append(" ${result.duplicates} already in the vault.")
                        if (result.skipped > 0) append(" ${result.skipped} unsupported item${plural(result.skipped)} skipped.")
                    },
                    error = if (result.incomplete) "The end of the backup file is damaged; everything before it was restored." else null,
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            refreshEntries(operationGeneration)
            if (isGenerationCurrent(operationGeneration)) {
                state = state.copy(
                    busy = null,
                    message = if (error is InterruptedIOException) "Restore stopped. Items restored so far were kept." else null,
                    error = if (error is InterruptedIOException) null else describeRestoreFailure(error),
                )
            }
        } catch (_: OutOfMemoryError) {
            if (isGenerationCurrent(operationGeneration)) {
                state = state.copy(busy = null, error = "This phone doesn't have enough memory to open this backup.")
            }
        } finally {
            passphrase.wipe()
            cancelRequested = false
        }
    }

    private fun storeRestoredThumbnail(key: ByteArray, id: String, content: ByteArray) {
        try {
            val jpeg = ImageCodec.thumbnailFromBytes(content)
            try {
                repository.storeThumbnail(key, id, jpeg)
            } finally {
                jpeg.wipe()
            }
        } catch (_: Exception) {
            // Regenerated lazily.
        } catch (_: OutOfMemoryError) {
            // Regenerated lazily.
        }
    }

    private fun describeRestoreFailure(error: Exception): String = when (error) {
        is VaultBackup.WrongBackupPassphraseException -> "Wrong passphrase, or the backup file is damaged."
        is VaultBackup.NotABackupException -> "That file isn't an Opaque Vault backup."
        else -> "The backup couldn't be read."
    }

    suspend fun wipeFreeSpace(passes: Int) = operationMutex.withLock {
        if (state.phase != Phase.UNLOCKED) return@withLock
        val operationGeneration = currentGeneration()
        cancelRequested = false
        try {
            state = state.copy(
                busy = Busy("Wiping free space", progress = 0f, detail = "Pass 1 of $passes", cancellable = true),
                error = null,
                message = null,
            )
            val result = withContext(Dispatchers.IO) {
                wiper.wipe(
                    passes = passes,
                    onProgress = { progress ->
                        postBusy(operationGeneration) {
                            val passFraction = if (progress.targetBytes > 0) progress.writtenBytes.toFloat() / progress.targetBytes else 1f
                            it.copy(
                                progress = ((progress.pass - 1) + passFraction.coerceIn(0f, 1f)) / progress.passes,
                                detail = "Pass ${progress.pass} of ${progress.passes} · ${bytes(progress.writtenBytes)} of " +
                                    "${bytes(progress.targetBytes)} · ${bytes(progress.bytesPerSecond)}/s",
                            )
                        }
                    },
                    shouldContinue = { isGenerationCurrent(operationGeneration) && !cancelRequested },
                )
            }
            if (isGenerationCurrent(operationGeneration)) {
                state = state.copy(
                    busy = null,
                    message = if (result.cancelled) {
                        "Wipe stopped. Its temporary files were removed."
                    } else {
                        "Overwrote ${bytes(result.totalWrittenBytes)} of free space in ${result.passesCompleted} " +
                            "pass${if (result.passesCompleted == 1) "" else "es"}. Temporary files were removed."
                    },
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            if (isGenerationCurrent(operationGeneration)) {
                state = state.copy(busy = null, error = "The wipe hit a storage error and stopped. Temporary files were removed.")
            }
        } finally {
            cancelRequested = false
        }
    }

    fun cancelCurrentOperation() {
        cancelRequested = true
        state.busy?.let { state = state.copy(busy = it.copy(detail = "Cancelling safely…", cancellable = false)) }
        // A provider read may be blocked inside native I/O and never reach shouldContinue(). Closing
        // its stream is the only reliable cooperative cancellation signal.
        Thread({ repository.cancelActiveImports() }, "cancel-vault-io").apply { isDaemon = true }.start()
    }

    // ---------------------------------------------------------------------------------------------
    // Optional high-security factors and verification
    // ---------------------------------------------------------------------------------------------

    fun prepareBiometricEnrollmentCipher(): Cipher {
        check(state.phase == Phase.UNLOCKED) { "Unlock the vault first" }
        return biometricKeyManager.enrollmentCipher()
    }

    suspend fun completeBiometricEnrollment(authenticatedCipher: Cipher) = operationMutex.withLock {
        try {
            withMasterKeyCopy { key -> withContext(Dispatchers.IO) { biometricKeyManager.finishEnrollment(authenticatedCipher, key) } }
            preferences.biometricKeyEnabled = true
            preferences.requireDeviceAuthentication = false // retire the old UI-only gate
            refreshSettings()
            state = state.copy(message = "Hardware-bound biometric unlock enabled. Your passphrase remains the recovery path.")
        } catch (_: Exception) {
            withContext(Dispatchers.IO) { biometricKeyManager.clear() }
            preferences.biometricKeyEnabled = false
            refreshSettings()
            state = state.copy(error = "Biometric key enrollment failed safely.")
        }
    }

    fun prepareBiometricUnlockCipher(): Cipher {
        check(preferences.biometricKeyEnabled && biometricKeyManager.isEnrolled()) { "Biometric key is not enrolled" }
        check(!preferences.nfcKeyEnabled || state.nfcKeySatisfied) { "Scan the NFC key first" }
        return biometricKeyManager.unlockCipher()
    }

    suspend fun unlockWithBiometric(authenticatedCipher: Cipher) = operationMutex.withLock {
        if (state.phase != Phase.LOCKED) return@withLock
        val generation = currentGeneration()
        try {
            val risk = deviceRiskAssessment.snapshot(preferences.strictDeviceIntegrity, preferences.signaturePinningEnabled)
            if (risk.blockUnlock) {
                state = state.copy(error = risk.warning, environmentWarning = risk.warning)
                return@withLock
            }
            if (preferences.nfcKeyEnabled && !state.nfcKeySatisfied) {
                state = state.copy(error = "Scan your enrolled NFC key first.")
                return@withLock
            }
            state = state.copy(busy = Busy("Unlocking with secure hardware"), error = null)
            val key = withContext(Dispatchers.IO) { biometricKeyManager.finishUnlock(authenticatedCipher) }
            if (!installMasterKeyIfCurrent(key, generation)) return@withLock
            val listing = withMasterKeyCopy { copy -> withContext(Dispatchers.IO) { repository.listEntries(copy) } }
            preferences.clearFailedUnlocks()
            preferences.lastSuccessfulUnlockEpochMillis = System.currentTimeMillis()
            state = state.copy(
                phase = Phase.UNLOCKED,
                busy = null,
                entries = listing.entries,
                corruptedObjectCount = listing.corruptedObjectCount,
                hardwareLevel = keyManager.hardwareLevel(),
                failedUnlockCount = 0,
                lockoutUntilEpochMillis = 0,
                error = null,
            )
            refreshPermissions()
        } catch (error: Exception) {
            clearSessionKey()
            if (error is BiometricKeyUnavailableException) {
                biometricKeyManager.clear()
                preferences.biometricKeyEnabled = false
                refreshSettings()
            }
            state = state.copy(phase = Phase.LOCKED, busy = null, entries = emptyList(), error = "Biometric key unavailable. Use your passphrase.")
        }
    }

    fun disableBiometricKey() {
        biometricKeyManager.clear()
        preferences.biometricKeyEnabled = false
        preferences.requireDeviceAuthentication = false
        refreshSettings()
        state = state.copy(message = "Hardware-bound biometric unlock disabled.")
    }

    /** Called from NFC reader mode. [enroll] is only accepted while the vault is unlocked. */
    fun onNfcKeyScanned(tagId: ByteArray, enroll: Boolean) {
        val copy = tagId.copyOf()
        mainHandler.post {
            try {
                if (enroll) {
                    if (state.phase != Phase.UNLOCKED) throw IllegalStateException("Unlock first")
                    nfcKeyManager.enroll(copy)
                    preferences.nfcKeyEnabled = true
                    refreshSettings()
                    state = state.copy(nfcKeySatisfied = true, message = "NFC possession factor enrolled.", error = null)
                } else if (preferences.nfcKeyEnabled && nfcKeyManager.matches(copy)) {
                    state = state.copy(nfcKeySatisfied = true, message = "NFC key accepted.", error = null)
                } else {
                    if (preferences.intruderLogEnabled) runCatching { intruderLog.append("Unrecognized NFC key") }
                    state = state.copy(error = "That is not the enrolled NFC key.")
                }
            } catch (_: Exception) {
                state = state.copy(error = "This NFC tag could not be used.")
            } finally {
                copy.wipe()
            }
        }
    }

    fun disableNfcKey() {
        nfcKeyManager.clear()
        preferences.nfcKeyEnabled = false
        refreshSettings()
        state = state.copy(nfcKeySatisfied = true, message = "NFC second factor disabled.")
    }

    suspend fun configureDuressPassphrase(current: CharArray, duress: CharArray, confirmation: CharArray) = operationMutex.withLock {
        try {
            validateNewPassphrase(duress, confirmation)
            require(!CryptoEngine.constantTimeEquals(current, duress)) { "Duress and vault passphrases must be different." }
            val verified = withContext(Dispatchers.IO) { keyManager.unlock(current) }
            val sameVault = try { withMasterKeyCopy { MessageDigest.isEqual(it, verified.masterKey) } } finally { verified.masterKey.wipe() }
            if (!sameVault) throw WrongPassphraseException()
            withContext(Dispatchers.IO) { duressManager.enroll(duress) }
            state = state.copy(duressEnabled = true, message = "Duress passphrase armed. Entering it at the lock screen destroys the vault key.")
        } catch (_: WrongPassphraseException) {
            state = state.copy(error = "Current vault passphrase is incorrect. Duress mode was not changed.")
        } catch (error: IllegalArgumentException) {
            state = state.copy(error = error.message)
        } catch (_: Exception) {
            state = state.copy(error = "Duress passphrase setup failed safely.")
        } finally {
            current.wipe(); duress.wipe(); confirmation.wipe()
        }
    }

    fun disableDuressPassphrase() {
        duressManager.clear()
        state = state.copy(duressEnabled = false, message = "Duress passphrase disabled.")
    }

    fun setStrictDeviceIntegrity(enabled: Boolean) {
        preferences.strictDeviceIntegrity = enabled
        refreshSettings()
        val risk = deviceRiskAssessment.snapshot(enabled)
        state = state.copy(signatureStatus = risk.signatureStatus, attestationSummary = risk.attestation.summary,
            message = if (enabled) "Strict hardware-attestation blocking enabled." else "Strict hardware-attestation blocking disabled.")
    }

    fun setSignaturePinningEnabled(enabled: Boolean) {
        preferences.signaturePinningEnabled = enabled
        refreshSettings()
        state = state.copy(message = if (enabled) "Official release-signature blocking enabled." else "Signature status will still be shown, but mismatches will not block unlock.")
    }

    fun setPanicGestureAction(action: PanicAction) { preferences.panicGestureAction = action; refreshSettings() }
    fun setPanicTileAction(action: PanicAction) { preferences.panicTileAction = action; refreshSettings() }

    fun setDeadManDays(days: Int) {
        preferences.deadManDays = days
        if (days > 0) preferences.lastSuccessfulUnlockEpochMillis = System.currentTimeMillis()
        refreshSettings()
        state = state.copy(message = if (days == 0) "Dead-man erase disabled." else "Dead-man erase armed for $days days; checked whenever the app starts.")
    }

    fun setIntruderLogEnabled(enabled: Boolean) {
        preferences.intruderLogEnabled = enabled
        if (!enabled) {
            preferences.captureIntruderPhoto = false
            intruderLog.clear()
            state.intruderPhoto?.let { if (!it.isRecycled) it.recycle() }
        }
        refreshSettings()
        state = state.copy(intruderEvents = emptyList(), intruderPhoto = null,
            message = if (enabled) "Encrypted failed-attempt log enabled." else "Intruder log disabled and erased.")
    }

    fun setCaptureIntruderPhoto(enabled: Boolean) {
        preferences.captureIntruderPhoto = enabled
        refreshSettings()
        state = state.copy(message = if (enabled) "Failed-unlock camera capture enabled. Android will show its camera privacy indicator." else "Failed-unlock camera capture disabled.")
    }

    fun loadIntruderLog() {
        val events = runCatching { intruderLog.read() }.getOrDefault(emptyList())
        val photoBytes = runCatching { intruderLog.readLatestPhoto() }.getOrNull()
        val photo = try {
            photoBytes?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }
        } finally { photoBytes?.wipe() }
        state.intruderPhoto?.let { if (it !== photo && !it.isRecycled) it.recycle() }
        state = state.copy(intruderEvents = events, intruderPhoto = photo)
    }

    fun clearIntruderLog() {
        intruderLog.clear()
        state.intruderPhoto?.let { if (!it.isRecycled) it.recycle() }
        state = state.copy(intruderEvents = emptyList(), intruderPhoto = null, message = "Intruder log erased.")
    }

    fun setStripMetadataOnExport(enabled: Boolean) { preferences.stripMetadataOnExport = enabled; refreshSettings() }

    suspend fun verifyVaultIntegrity() = operationMutex.withLock {
        if (state.phase != Phase.UNLOCKED) return@withLock
        val generation = currentGeneration()
        cancelRequested = false
        var verified = 0
        var failed = 0
        val buffer = ByteArray(256 * 1024)
        try {
            withMasterKeyCopy { key ->
                state.entries.forEachIndexed { index, entry ->
                    if (cancelRequested || !isGenerationCurrent(generation)) return@forEachIndexed
                    state = state.copy(busy = Busy("Verifying vault integrity", index.toFloat() / state.entries.size.coerceAtLeast(1),
                        "${index + 1} of ${state.entries.size} · ${entry.displayName}", cancellable = true))
                    val ok = withContext(Dispatchers.IO) {
                        runCatching {
                            val expected = repository.contentDigest(key, entry.id)
                            val digest = MessageDigest.getInstance("SHA-256")
                            try {
                                repository.openReader(key, entry.id).use { reader ->
                                    var offset = 0L
                                    while (offset < reader.size) {
                                        if (cancelRequested) throw InterruptedIOException()
                                        val count = reader.read(offset, buffer, 0, buffer.size)
                                        if (count <= 0) throw IOException("Encrypted item ended early")
                                        digest.update(buffer, 0, count); offset += count
                                    }
                                }
                                val actual = digest.digest()
                                try { if (!MessageDigest.isEqual(actual, expected)) throw VaultIntegrityException("Digest mismatch") }
                                finally { actual.wipe() }
                            } finally { expected.wipe() }
                        }.isSuccess
                    }
                    if (ok) verified++ else failed++
                }
            }
            state = state.copy(busy = null, message = "Integrity check complete: $verified verified.",
                error = if (failed > 0) "$failed item${plural(failed)} failed verification and were not opened or changed." else null)
        } finally {
            buffer.wipe(); cancelRequested = false
            if (state.busy?.label == "Verifying vault integrity") state = state.copy(busy = null)
        }
    }

    suspend fun performPanicAction(action: PanicAction) {
        when (action) {
            PanicAction.OFF -> Unit
            PanicAction.LOCK -> lock()
            PanicAction.ERASE -> cryptoErase()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Settings and device state
    // ---------------------------------------------------------------------------------------------

    fun refreshPermissions() {
        state = state.copy(
            canSearchPhotos = cleaner.canSearchMediaStore(),
            hasFullPhotoAccess = cleaner.hasFullMediaAccess(),
            canDeleteWithoutConfirmation = cleaner.canDeleteWithoutConfirmation(),
        )
    }

    fun photoAccessPermissions(): Array<String> = cleaner.mediaReadPermissions()

    fun markDeviceAuthenticationSatisfied() {
        state = state.copy(deviceAuthenticationSatisfied = true, error = null)
    }

    fun markDeviceAuthenticationUnavailable() {
        state = state.copy(error = "No fingerprint, face or screen lock is set up on this phone.")
    }

    /** Legacy API retained for upgrades; the old UI-only gate is never enabled again. */
    fun setRequireDeviceAuthentication(enabled: Boolean) {
        if (!enabled) disableBiometricKey()
    }

    fun setAutoLockSeconds(seconds: Int) {
        preferences.autoLockSeconds = seconds
        refreshSettings()
    }

    fun setInactivityLockMinutes(minutes: Int) {
        preferences.inactivityLockMinutes = minutes
        refreshSettings()
    }

    fun setWipeAfterTenFailures(enabled: Boolean) {
        preferences.wipeAfterTenFailures = enabled
        refreshSettings()
    }

    fun setAutoPlayVideos(enabled: Boolean) {
        preferences.autoPlayVideos = enabled
        refreshSettings()
    }

    fun setLoopVideos(enabled: Boolean) {
        preferences.loopVideos = enabled
        refreshSettings()
    }

    fun setStartVideosMuted(enabled: Boolean) {
        preferences.startVideosMuted = enabled
        refreshSettings()
    }

    fun setLockOnScreenOff(enabled: Boolean) {
        preferences.lockOnScreenOff = enabled
        refreshSettings()
    }

    fun setGridSize(size: app.opaquevault.security.GridSize) {
        preferences.gridSize = size
        refreshSettings()
    }

    fun setSortOrder(order: app.opaquevault.security.SortOrder) {
        preferences.sortOrder = order
        refreshSettings()
    }

    fun setVideoSkipSeconds(seconds: Int) {
        preferences.skipSeconds = seconds
        refreshSettings()
    }

    fun setKeepScreenOnDuringPlayback(enabled: Boolean) {
        preferences.keepScreenOnDuringPlayback = enabled
        refreshSettings()
    }

    fun setConfirmVaultDeletion(enabled: Boolean) {
        preferences.confirmVaultDeletion = enabled
        refreshSettings()
    }

    fun setWipeFailureThreshold(attempts: Int) {
        preferences.wipeFailureThreshold = attempts
        refreshSettings()
    }

    fun setHomeTitle(title: String) {
        preferences.homeTitle = title
        refreshSettings()
    }

    fun setDisguiseMode(mode: DisguiseMode) {
        preferences.disguiseMode = mode
        refreshSettings()
        state = state.copy(
            message = if (mode == DisguiseMode.CALCULATOR) {
                "Calculator disguise armed. It appears after the vault locks; hold = to reveal the real lock screen."
            } else {
                "Calculator disguise turned off."
            },
        )
    }

    fun setLauncherIdentity(identity: LauncherIdentity) {
        if (launcherIdentityManager.apply(identity)) {
            preferences.launcherIdentity = identity
            refreshSettings()
            state = state.copy(message = "Launcher name and icon updated. Your launcher may take a moment to refresh.")
        } else {
            state = state.copy(error = "Android couldn't finish changing the launcher icon. The previous entry remains available.")
        }
    }

    fun setAfterImportAction(action: AfterImportAction) {
        preferences.afterImport = action
        refreshSettings()
    }

    fun setConfirmSharedImports(enabled: Boolean) {
        preferences.confirmSharedImports = enabled
        refreshSettings()
    }

    fun setThemeMode(mode: ThemeMode) {
        preferences.themeMode = mode
        refreshSettings()
    }

    fun setDynamicColor(enabled: Boolean) {
        preferences.dynamicColor = enabled
        refreshSettings()
    }

    fun autoLockDelayMillis(): Long = preferences.autoLockSeconds * 1_000L

    /** 0 means never lock for inactivity. */
    fun inactivityLockMillis(): Long = preferences.inactivityLockMinutes * 60_000L

    fun reportPickerLaunchFailure(error: Throwable) {
        state = state.copy(
            error = "Android refused to open the picker (${error.javaClass.simpleName}). " +
                "You can also share photos and videos to Opaque Vault from your Gallery app.",
        )
    }

    fun reportMessage(message: String) {
        state = state.copy(message = message)
    }

    fun clearTransientMessage() {
        state = state.copy(message = null, error = null)
    }

    override fun close() {
        lock()
    }

    // ---------------------------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------------------------

    private fun refreshSettings() {
        state = state.copy(settings = preferences.snapshot())
    }

    private suspend fun refreshEntries(generation: Long) {
        if (!isGenerationCurrent(generation)) return
        val key = copyMasterKeyOrNull() ?: return
        try {
            val listing = withContext(Dispatchers.IO) { repository.listEntries(key) }
            if (isGenerationCurrent(generation)) {
                state = state.copy(entries = listing.entries, corruptedObjectCount = listing.corruptedObjectCount)
            }
        } finally {
            key.wipe()
        }
    }

    /** Applies a progress update from a worker thread on the main thread. */
    private fun postBusy(generation: Long, transform: (Busy) -> Busy) {
        mainHandler.post {
            val busy = state.busy ?: return@post
            if (isGenerationCurrent(generation)) state = state.copy(busy = transform(busy))
        }
    }

    private fun releaseDecryptedImages() {
        thumbnailCache.evictAll()
        imageCache.evictAll()
        unavailableThumbnails.clear()
    }

    private fun scheduleBitmapRecycle(bitmap: Bitmap) {
        // Let Compose replace an on-screen bitmap before zeroing/releasing its native pixel storage.
        mainHandler.postDelayed({ if (!bitmap.isRecycled) bitmap.recycle() }, BITMAP_RELEASE_DELAY_MILLIS)
    }

    private fun bytes(value: Long): String = Formatter.formatShortFileSize(appContext, value)

    private fun plural(count: Int): String = if (count == 1) "" else "s"

    private fun destroyAllSecurityMaterial() {
        VaultEmergencyEraser.erase(appContext)
    }

    private fun enforceDeadManDeadline() {
        val days = preferences.deadManDays
        val last = preferences.lastSuccessfulUnlockEpochMillis
        if (days <= 0 || last <= 0 || !keyManager.isInitialized()) return
        val now = System.currentTimeMillis()
        if (now >= last && now - last >= days * 24L * 60L * 60L * 1_000L) {
            destroyAllSecurityMaterial()
            startupDeadManTriggered = true
        }
    }

    private fun initialState(): UiState {
        enforceDeadManDeadline()
        if (preferences.biometricKeyEnabled && !biometricKeyManager.isEnrolled()) preferences.biometricKeyEnabled = false
        if (preferences.nfcKeyEnabled && !nfcKeyManager.isEnrolled()) preferences.nfcKeyEnabled = false
        val risk = deviceRiskAssessment.snapshot(preferences.strictDeviceIntegrity, preferences.signaturePinningEnabled)
        return UiState(
            phase = if (keyManager.isInitialized()) Phase.LOCKED else Phase.SETUP,
            settings = preferences.snapshot(),
            failedUnlockCount = preferences.failedUnlockCount,
            lockoutUntilEpochMillis = preferences.lockoutUntilEpochMillis,
            deviceAuthenticationSatisfied = true,
            nfcKeySatisfied = !preferences.nfcKeyEnabled,
            environmentWarning = risk.warning,
            signatureStatus = risk.signatureStatus,
            attestationSummary = risk.attestation.summary,
            message = if (startupDeadManTriggered) "Vault erased because its dead-man deadline expired." else null,
            duressEnabled = duressManager.isEnabled(),
            canSearchPhotos = cleaner.canSearchMediaStore(),
            hasFullPhotoAccess = cleaner.hasFullMediaAccess(),
            canDeleteWithoutConfirmation = cleaner.canDeleteWithoutConfirmation(),
        )
    }

    private fun heapMiB(): Int = maxOf(activityManager.memoryClass, activityManager.largeMemoryClass)

    private fun vaultKdfParameters(): VaultKeyManager.KdfParameters {
        val memoryKiB = when {
            heapMiB() >= 512 -> 262_144
            heapMiB() >= 256 -> 131_072
            else -> 65_536
        }
        val parallelism = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
        return VaultKeyManager.KdfParameters(memoryKiB, iterations = 3, parallelism = parallelism)
    }

    /**
     * The backup has no hardware key behind it, so its passphrase gets the heaviest Argon2id cost the
     * phone can afford: 256 MB, 4 passes, one lane (this implementation is single-threaded, so one
     * lane gives an attacker no parallelism the user doesn't also have).
     */
    private fun backupKdfParameters(): VaultKeyManager.KdfParameters {
        val memoryKiB = when {
            heapMiB() >= 384 -> 262_144
            heapMiB() >= 192 -> 131_072
            else -> 65_536
        }
        return VaultKeyManager.KdfParameters(memoryKiB, iterations = 4, parallelism = 1)
    }

    private fun thumbnailCacheKiB(): Int =
        (Runtime.getRuntime().maxMemory() / 1024 / 8).coerceAtMost(48L * 1024L).toInt()

    private fun validateNewPassphrase(passphrase: CharArray, confirmation: CharArray) {
        require(passphrase.size in MIN_PASSPHRASE_CHARS..MAX_PASSPHRASE_CHARS) {
            "Use at least $MIN_PASSPHRASE_CHARS characters. Six random words is ideal."
        }
        require(CryptoEngine.constantTimeEquals(passphrase, confirmation)) { "Passphrases don't match." }
    }

    private fun installMasterKeyIfCurrent(newKey: ByteArray, expectedGeneration: Long): Boolean {
        require(newKey.size == CryptoEngine.KEY_BYTES)
        return synchronized(keyMonitor) {
            if (sessionGeneration != expectedGeneration) {
                newKey.wipe()
                false
            } else {
                masterKey?.wipe()
                masterKey = newKey
                true
            }
        }
    }

    private fun currentGeneration(): Long = synchronized(keyMonitor) { sessionGeneration }

    private fun isGenerationCurrent(expectedGeneration: Long): Boolean =
        synchronized(keyMonitor) { sessionGeneration == expectedGeneration }

    private fun clearSessionKey() {
        synchronized(keyMonitor) {
            sessionGeneration++
            masterKey?.wipe()
            masterKey = null
        }
    }

    private fun copyMasterKeyOrNull(): ByteArray? = synchronized(keyMonitor) { masterKey?.copyOf() }

    private suspend fun <T> withMasterKeyCopy(block: suspend (ByteArray) -> T): T {
        val copy = copyMasterKeyOrNull() ?: throw IllegalStateException("Vault is locked")
        return try {
            block(copy)
        } finally {
            copy.wipe()
        }
    }

    companion object {
        const val MIN_PASSPHRASE_CHARS = 16
        private const val MAX_PASSPHRASE_CHARS = 256
        private const val MAX_BATCH_IMPORTS = 1000
        private const val BITMAP_RELEASE_DELAY_MILLIS = 1_500L
        private const val PROGRESS_MIN_INTERVAL_MILLIS = 250L
        private const val VIDEO_PROBE_TIMEOUT_SECONDS = 20L
    }
}
