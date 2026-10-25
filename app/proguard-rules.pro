# Vault models are serialized explicitly; no reflection-based keep rules are needed.
# Preserve Bouncy Castle's low-level Argon2 implementation metadata.
-keepattributes InnerClasses,EnclosingMethod
-dontwarn org.bouncycastle.**
