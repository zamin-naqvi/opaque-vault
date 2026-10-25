# Architecture

Opaque Vault is a single-process, offline Android application. Its security boundary is Android application isolation plus keys generated in Android Keystore. There is no account system, backend, analytics SDK, advertising SDK, or network permission.

## Components

| Area | Responsibility |
|---|---|
| `VaultController` | Owns the unlocked session key, serializes mutating operations, applies lock policy, and exposes UI state |
| `VaultKeyManager` | Creates and opens the vault header; combines the passphrase and device factor |
| `KeystoreManager` | Generates the non-exportable AES device key and reports its hardware level |
| `VaultRepository` | Authenticated item metadata, streaming ciphertext, thumbnails, listing, deletion, and random-access readers |
| `VaultBackup` | Portable, passphrase-only streaming backup and restore |
| `MediaExporter` | Direct-to-destination decryption, digest verification, pending MediaStore publication, and optional EXIF removal |
| `ProviderSourceStream` | Cancellable provider reads with stall detection for OEM Gallery providers |
| `DeviceRiskAssessment` | Debug/root/accessibility checks, signature status, and hardware attestation summary |

## Key hierarchy

A new vault generates two independent 256-bit random values:

1. **Device factor** — encrypted by an AES-256 key generated inside Android Keystore.
2. **Master key** — wraps each item's independently generated file key.

The passphrase is processed with Argon2id. HKDF-SHA-256 combines the Argon2id result and decrypted device factor. The resulting wrapping key encrypts the master key with AES-256-GCM. The master key is kept only in process memory while unlocked and overwritten on lock.

The vault header authenticates its version, KDF parameters, salt, device envelope, and master-key envelope. Changing the passphrase creates a fresh salt and wrapping key but does not re-encrypt media.

## Item lifecycle

Each import generates a fresh 256-bit file key. Content is encrypted with Tink AES-GCM-HKDF streaming AEAD in 1 MiB segments. Metadata holds the wrapped file key, MIME type, display name, sizes, import time, optional duration, content format, and SHA-256 digest. Metadata and thumbnails are separately encrypted and bound to the item identifier through associated data.

Imports use temporary files inside app-private storage. Ciphertext and metadata are read back and authenticated before atomic rename commits them. Recent `.part` files are protected from cleanup while an import may still be active.

Video playback uses an authenticated seekable channel over ciphertext. Photos are bounded before in-memory decryption. Decrypted pixels and session keys are released on lock.

## Export boundary

Exports require the vault passphrase again. Plaintext travels from an authenticated vault reader directly to a MediaStore or Storage Access Framework output stream; it is not staged in app storage. The output digest must match authenticated metadata. Failed or cancelled outputs are deleted. MediaStore files remain pending and invisible to Gallery until verification succeeds.

Optional photo metadata removal runs against the still-pending destination and removes GPS, camera identity, serial-number, user-comment, and capture-time EXIF fields.

## Optional factors

- **Biometric key:** a separate Android Keystore AES key requires `BIOMETRIC_STRONG` for each use. It encrypts a master-key envelope and is passed to `BiometricPrompt` as a `CryptoObject`. Passphrase unlock remains a recovery path.
- **NFC factor:** stores only a salted HMAC verifier of a dedicated tag identifier. It gates both passphrase and biometric unlock when enabled. It is not a FIDO2 implementation.
- **Duress verifier:** a separately salted Argon2id verifier. A match invokes the common emergency key-destruction path.
- **Intruder log:** timestamps and generic failure reasons are AES-GCM encrypted under a separate Android Keystore key. Attempted passphrases are never recorded.

## Destructive actions

Explicit erase, automatic failure erase, duress erase, dead-man erase, and panic erase share one path. It deletes the device key first, then vault ciphertext, optional-factor state, encrypted logs, and local preferences. Logical deletion is immediate; physical flash-cell erasure cannot be guaranteed because of wear leveling.

## Concurrency and cancellation

Long mutations are serialized by a coroutine mutex. Session generations invalidate stale work after lock. Provider imports have explicit cancellation signals, close tracked streams, poll blocked descriptors, and detect prolonged no-progress conditions. Export, backup, restore, integrity scan, and free-space wiping expose bounded progress and cancellation where safe.
