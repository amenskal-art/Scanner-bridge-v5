# Build the APK with GitHub Actions (no Android Studio, no terminal)

You upload the project to a free GitHub repo; GitHub's servers compile it and
hand you back the APK. ~10 minutes the first time.

## 1. Make a GitHub account
Go to https://github.com and sign up (free) if you don't have one.

## 2. Create a new repository
- Click the **+** (top-right) -> **New repository**.
- Name it e.g. `scanner-bridge`.
- Leave it **Public** (free Actions minutes) or Private (also fine).
- Click **Create repository**.

## 3. Upload the project files
On the empty repo page:
- Click **uploading an existing file** (the link in the quick-setup box).
- Unzip `ScannerBridge.zip` on your computer first, then drag **the contents**
  (the `app` folder, `build.gradle`, `settings.gradle`, the `.github` folder,
  etc.) into the upload area.
  - IMPORTANT: upload the files INSIDE the ScannerBridge folder, not the
    folder itself, so `build.gradle` sits at the repo root.
  - Make sure the hidden **`.github`** folder uploads too (it holds the build
    workflow). If drag-drop skips it, see step 6 for a manual fallback.
- Scroll down, click **Commit changes**.

## 4. Watch it build
- Go to the **Actions** tab of your repo.
- You'll see a run called **Build APK** start automatically. Click it.
- Wait for the green check (first run ~5-10 min while it downloads the SDK and
  the AUSBC native libraries).

## 5. Download the APK
- On the finished run's summary page, scroll to **Artifacts**.
- Download **ScannerBridge-debug-apk**. It's a zip containing
  `app-debug.apk`.
- Copy that APK to your phone and install it (you'll need to allow
  "install from unknown sources").

## 6. If the `.github` folder didn't upload
GitHub's web uploader sometimes hides dot-folders. Create the file manually:
- In the repo, click **Add file -> Create new file**.
- In the name box type exactly:  `.github/workflows/build-apk.yml`
  (typing the slashes creates the folders).
- Paste the contents of `.github/workflows/build-apk.yml` from the project.
- **Commit changes**. The build starts on commit.

## Notes
- This produces a **debug** APK: installable and fully functional, just signed
  with a debug key (fine for your own use, not for the Play Store).
- Every time you change a file and commit, a fresh APK builds automatically.
- To rebuild without changing anything: Actions tab -> Build APK ->
  **Run workflow**.
- If a build fails, open the failed step's log — it's usually a dependency or an
  AUSBC `onPreviewData` signature mismatch (see README's last section).

## Troubleshooting: "Could not find libnative-3.3.3"

If a build fails with `Could not find libnative-3.3.3.jar` (or any `lib...-3.3.3`
from AndroidUSBCamera), it means Gradle couldn't fetch the full AUSBC module set.

AUSBC 3.3.3 and its sibling modules live in the **Liferay public** Maven mirror,
not on Maven Central, and only flakily on JitPack. The project's
`settings.gradle` already points at the Liferay repo to fix this:

```
maven { url 'https://repository.liferay.com/nexus/content/repositories/public/' }
```

If you ever still hit this, make sure that line is present in `settings.gradle`
in your repo (open the file on GitHub and check). A transient network blip can
also cause it — just re-run the workflow (Actions tab -> Build APK ->
Run workflow).
