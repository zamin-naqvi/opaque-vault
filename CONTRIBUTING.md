# Contributing

Thanks for taking the time to improve Opaque Vault. Changes are reviewed more strictly than a typical media app because mistakes can expose data or make it permanently unrecoverable.

## Before opening a change

1. Search existing issues and discussions.
2. For a security vulnerability, follow [SECURITY.md](SECURITY.md) instead of opening a public issue.
3. Keep the application offline. Do not add `INTERNET`, analytics, crash reporting, advertising, accounts, or cloud dependencies.
4. Explain the threat being addressed and what remains out of scope.

## Development setup

Use Android Studio, JDK 17, and Android SDK 37.

```powershell
.\gradlew.bat :app:assembleDebug
```

Private release signing files are not required and must never be added to a branch.

## Pull requests

A focused pull request should include:

- a concise explanation of user-visible behavior;
- security and data-loss implications;
- Android versions tested;
- exact validation commands;
- screenshots for UI changes, using synthetic data only.

Before submitting:

```powershell
.\gradlew.bat clean :app:assembleDebug :app:lintDebug --console=plain
```

Do not weaken `FLAG_SECURE`, private keyboard behavior, authenticated reads, digest checks, atomic writes, cancellation cleanup, or key wiping merely to simplify an implementation.

## Code guidelines

- Prefer standard Android and audited cryptographic primitives.
- Never invent a cipher, KDF, MAC, or signature construction.
- Keep plaintext lifetime and allocation size bounded.
- Domain-separate associated data and KDF contexts.
- Treat content-provider metadata and streams as untrusted.
- Fail closed on authentication or integrity errors.
- Preserve upgrade compatibility with existing vault headers and items.
- Destructive behavior must be opt-in, clearly described, and independently testable.
- Do not log passphrases, keys, decrypted names, media contents, or private URIs.

## Test data

Use an emulator and generated media. Never commit files from a personal vault. Never run destructive QA against a physical device containing user data.
