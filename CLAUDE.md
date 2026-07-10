# Headwind MDM — Fleet Fork

## Project overview

This is a fork of the open-source Headwind MDM Android launcher
(`h-mdm/hmdm-android`, community edition), currently at v6.36.2, deployed as
**device owner** on fleet devices. FreeKiosk (`com.freekiosk`) runs alongside
it as the actual kiosk/POS app that end users interact with.

## Fleet customization: lock-task whitelist patch

- **Problem**: Community edition doesn't call
  `DevicePolicyManager.setLockTaskPackages()`. FreeKiosk self-pins via
  `startLockTask()`, which without a whitelist falls into Android's *screen
  pinning* (a user-consent prompt). If FreeKiosk's immersive mode buries that
  prompt, the device freezes in consent-limbo (taps consumed system-side).
- **Fix**: `Utils.applyLockTaskWhitelist()`
  (`app/src/main/java/com/hmdm/launcher/util/Utils.java`) whitelists
  `com.freekiosk` as device owner so `startLockTask()` goes silent instead —
  no prompt, no limbo, and the unpin gesture is disabled (stronger kiosk
  posture).
- Called from `MainActivity.onCreate()`
  (`app/src/main/java/com/hmdm/launcher/ui/MainActivity.java`), idempotent,
  re-applied on every launcher start as a safety net — the whitelist persists
  in device policy across reboots, but this guards against drift.
- To whitelist another self-pinning kiosk app in the future, add its package
  name to `LOCK_TASK_EXTRA_PACKAGES` in `Utils.java`.
- The same patch is also saved standalone at `hmdm-locktask-whitelist-v1.patch`
  (repo root).

## CI build workflow

`.github/workflows/build-hmdm-launcher.yml`, manually triggered
(`workflow_dispatch`). Builds and signs the launcher with the fleet's own
keystore (repo secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`,
`KEY_PASSWORD`). Verifies the whitelist patch is present in source before
building.

**Critical constraint — do not break this**: since this APK installs as
device owner, Android only accepts updates signed with the *same key* used at
enrollment. Losing the keystore means the entire fleet needs a factory reset
to take any future launcher update. The keystore itself was removed from git
history (commit `3f14232`) and must never be re-committed — it's fed to CI
only via secrets.

## Working conventions

- Branch in use: `claude/headwind-feekiosk-requirements-7r7vyf` (current fleet
  customization branch, off `master` which tracks upstream).
- Custom fleet patches are marked inline with `// CUSTOM (fleet patch): ...`
  comments — grep for `CUSTOM (fleet patch)` to find all deviations from
  upstream.
- New FreeKiosk/Headwind MDM requirements for this fleet's use case should
  follow the same pattern: a small, targeted patch plus a comment explaining
  the *why*, not the *what*.
