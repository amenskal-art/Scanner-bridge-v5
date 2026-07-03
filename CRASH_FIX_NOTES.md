# Startup crash fix (Android 14+ / your Honor Android 16)

## The crash
```
java.lang.IllegalArgumentException: Targeting U+ (version 34 and above)
disallows creating or retrieving a PendingIntent with FLAG_MUTABLE,
an implicit Intent ... use FLAG_IMMUTABLE.
```
It fired right after granting the camera permission, then the app closed.

## Cause
This is NOT our code. The AUSBC camera library (libausbc 3.3.3) registers its
USB-permission receiver using a `PendingIntent` with `FLAG_MUTABLE` + an
implicit intent. Android 14 (API 34) and above forbid that for security, so the
OS throws the instant AUSBC initializes the camera. AUSBC 3.3.3 predates this
rule and we can't edit its compiled code.

## Fix applied
Lowered the app's `targetSdk` from 35 to **33** in `app/build.gradle`:
```
targetSdk 33
```
Below API 34 the new PendingIntent restriction isn't enforced, so AUSBC's
receiver registers fine and the camera opens. The app still installs and runs
normally on Android 16 -- it just opts out of the API-34 behaviour change that
AUSBC can't yet satisfy. `compileSdk` stays at 35.

## If you later want targetSdk 34+
You'd need an AUSBC build that uses FLAG_IMMUTABLE (a newer release or a forked
libausbc). Until then, targetSdk 33 is the clean, working choice.
