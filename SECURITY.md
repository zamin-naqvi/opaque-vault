# Security Policy

## Supported version

Security fixes are made against the latest source and latest published GitHub release. Older APKs should be treated as unsupported once a newer security release is available.

## Reporting a vulnerability

Please do **not** open a public issue for a suspected vulnerability involving key handling, authentication bypass, plaintext exposure, destructive actions, or exported Android components.

Use GitHub's **Report a vulnerability** button under the repository's Security tab to open a private security advisory. Include:

- affected commit or version;
- Android version and device model;
- exact reproduction steps;
- expected and observed behavior;
- logs or proof-of-concept code with personal data removed;
- whether exploitation requires root, ADB, an unlocked session, or physical access.

A report will be acknowledged as soon as practical. Please allow time to reproduce and prepare a fix before public disclosure.

## Scope

Particularly useful reports cover:

- recovery of plaintext or keys while the vault is locked;
- bypass of passphrase, biometric CryptoObject, or enabled NFC gating;
- unauthenticated modification accepted as valid media or metadata;
- plaintext files left in app or shared storage after failure;
- exported component, intent, or URI permission flaws;
- panic, duress, dead-man, or erase behavior that can trigger outside its documented policy;
- release-signature or attestation enforcement bypasses.

## Known boundaries

Reports that assume a fully compromised kernel/root environment, physical screen photography, flash wear-level recovery after logical deletion, or cloning of an NFC tag identifier are still welcome as hardening ideas, but these are documented limitations rather than guaranteed protection boundaries.

## Sensitive material

Never attach a real vault, passphrase, keystore, signing properties, device backup, personal photo, or private device identifier. Reproduce with a disposable emulator and synthetic media whenever possible.
