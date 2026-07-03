# Fix the "Could not find libnative-3.3.3" build error

## Why it's failing
Your GitHub repo's `settings.gradle` only lists **jitpack.io** as the place to
find AUSBC. But AUSBC 3.3.3's `libnative` module isn't reliably on JitPack --
it lives in the **Liferay public** Maven mirror. The build can't find it, so it
stops. (The log also shows compileSdk 36, another sign the repo files drifted
from the corrected ones.)

## The fix: replace 2-3 files in your repo

You have two options. Option A is surgical; Option B is the clean reset.

### Option A -- replace just the build files (fastest)
Replace these files in your GitHub repo with the downloaded versions:

1. **`settings.gradle`** (repo root)  -> use the downloaded `settings.gradle`
2. **`app/build.gradle`**            -> use the downloaded `app-build.gradle`
   (rename it to `build.gradle` when you put it in the `app/` folder)
3. **`gradle.properties`** (repo root) -> use the downloaded `gradle.properties`

How to replace a file on github.com:
- Open the file in your repo, click the pencil (Edit) icon.
- Select all, delete, paste the new contents.
- Commit changes.

The one that actually fixes the error is `settings.gradle` (it adds the Liferay
repository). The other two prevent the compileSdk warning/error.

### Option B -- replace the whole project (ends the drift for good)
Your repo and these files have gotten out of sync over time. If you want a
clean slate, re-upload the full corrected project from `ScannerBridge.zip` over
your repo contents and commit. This guarantees everything matches.

## After committing
The build re-runs automatically (Actions tab). The libnative error should be
gone. If a NEW/different error appears, that's progress -- send it over.

## The key line (so you can verify it's there)
Your `settings.gradle` MUST contain this inside the repositories block:

    maven { url 'https://repository.liferay.com/nexus/content/repositories/public/' }

If that line isn't in the version in your repo, the build will keep failing.
