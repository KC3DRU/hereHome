# SECURITY-REVIEW — pre-publication secrets / PII audit

Audit of this repository as it stands on branch `main` (commits `3850494` "Baseline: Here app
v1.0 (2 widgets) as received" and `0db0a3a` "Add 'Moving On' home-screen widget"), including
full git history, ignore rules, working tree, and runtime privacy behaviour.

> **Redaction note:** the username-bearing path that finding SR-1 quotes is masked here as
> `/home/<user>/…` because this report itself will be published. Its exact location is given
> so it can be located and removed without ambiguity.

## Verdict

**Not safe to publish today.** The tracked content contains no cryptographic secrets, no real
phone numbers, no email addresses, and no recipient lists — but it does leak the author's
local username through one absolute path in `README.md` (and that same line is baked into
baseline commit `3850494`, so scrubbing HEAD alone is not enough), and
`app/src/main/java/com/example/here/SmsSender.kt` logs every recipient's phone number plus the
full SMS body — including live GPS coordinates — to logcat, in a repo that advertises privacy.
Before publishing: remove the absolute path from `README.md` and rewrite history (or publish a
fresh squashed tree), reduce the `Log.d` line to a non-PII counter, set
`android:allowBackup="false"` (or add exclusion rules), drop the unused `INTERNET` permission,
and delete the dead Google Play Services ProGuard rules. None of this is catastrophic, but all
of it is visible on day one to anyone who reads the repo — including the git history.

## Findings

| ID | Severity | Where | Issue | Fix |
|----|----------|-------|-------|-----|
| SR-1 | High | `README.md:66` (also in commit `3850494`) | Building section contains the absolute path `` cd /home/<user>/data/hereHome`` (username redacted here), exposing the author's local username and directory layout. `git log --all -p -S'/home/'` confirms exactly one occurrence, introduced in `3850494`. | Change to a relative instruction (e.g. `cd hereHome` after cloning), then rewrite history (`git filter-repo` / amended squashed initial commit) so the baseline blob no longer ships it. |
| SR-2 | High | `app/src/main/java/com/example/here/SmsSender.kt:42` | `Log.d(TAG, "SMS sent to $sanitizedNumber: $message")` writes every recipient's phone number and the complete message body — which contains the user's live GPS coordinates via the Maps URL — to logcat on every send. Log is plain text on-device. | Log only an outcome counter, e.g. `Log.i(TAG, "SMS queued")` (no number, no body). Keep `Log.e(TAG, "Failed to send SMS", e)` at line 45, which carries no PII. |
| SR-3 | Medium | `app/src/main/AndroidManifest.xml:17` | `android:allowBackup="true"` with no `dataExtractionRules` / `fullBackupContent`: the recipient phone-number list (stored in SharedPreferences `here_settings`, key `recipients`) is eligible for cloud/device backup and ADB `backup.ab` extraction. | Set `android:allowBackup="false"`, or add backup-content rules that exclude the shared-preferences file. |
| SR-4 | Medium | `app/src/main/AndroidManifest.xml:13` | `<uses-permission android:name="android.permission.INTERNET" />` is declared but never used — no socket/HTTP/network API appears anywhere in `app/src/main/java` — and it contradicts the README's "No internet connection is required" claim. A privacy-focused app requesting net access invites scrutiny. | Remove the permission (the Maps URL is plain text). |
| SR-5 | Low | `app/proguard-rules.pro:3-4` | `-keep class com.google.android.gms.location.** { *; }` and `-keep class com.google.android.gms.common.** { *; }` are dead rules — there is no GMS dependency in `app/build.gradle.kts`. They contradict the repo's "Zero Google Play Services" claim and suggest a hidden dependency to a reader. | Delete both lines (an empty rules file is fine). |
| SR-6 | Low | `.gitignore` | Coverage gaps verified with `git check-ignore -v`: `.env`, `id_rsa`, `server.key`, `secrets.properties`, `backup.ab`, `hereHome.apk` are all **not** matched by any pattern. A future signing/key step could accidentally create a sensitive file that shows up as "just another untracked file". | Add patterns: `.env`, `.env.*`, `*.key`, `id_rsa*`, `secrets*`, `*.ab`, `*.apk` (build outputs are already under ignored `build/` dirs; this guards strays). |
| SR-7 | Info | working tree, correctly git-ignored: `local.properties`, `CHECKPOINT.md`, `WP1.md`, `WP2.md` | These contain username-bearing absolute paths (e.g. `local.properties` has `sdk.dir=/home/<user>/Android/Sdk`) and internal session notes. `git check-ignore -v` confirms each is matched (`local.properties` rule line 6, `CHECKPOINT.md` line 36, `WP*.md` line 39), and `git log --all -- <paths>` confirms none was ever committed. Hazard only if someone `git add -f`s them. | Leave ignored; do not force-add. Consider deleting `CHECKPOINT.md`/`WP*.md` from disk once the project ships. |
| SR-8 | Info | `app/src/main/java/com/example/here/MainActivity.kt` (`showConfirmationDialog`, `showPartialSuccessDialog`, `Toast` in `handleIntent`) | After a send, a dialog renders the full message (incl. coordinates) and all recipient numbers on screen; a Toast echoes the widget type. UI-only, nothing persisted beyond SR-2's log line — but shoulder-surfing/recording context worth knowing. | Optional: collapse the dialog to counts, or leave as a deliberate UX choice. |

## History hygiene

- `git log --all -p` (both commits, full diffs) was grepped for paths, usernames, secret
  keywords, emails, phone-like strings, and coordinate-like decimal pairs.
- **One sensitive string exists in history:** the README absolute path quoted in SR-1, added in
  baseline commit `3850494` (`+cd /home/<user>/data/hereHome`). It is still present at HEAD and
  in the baseline blob.
- Everything else in history is clean. The file list of every commit ever made (`git log --all
  --name-only`) contains only the 36 currently-tracked project files — `local.properties`,
  `CHECKPOINT.md`, `WP*.md`, keystores, and build outputs have never been committed in any commit.
- **Recommended remedy (do before making the repo public):** since the repo is local-only with
  two commits and no remote, rewrite rather than annotate — either `git filter-repo` to scrub
  the path string, or (simpler) build the intended clean tree, squash it into a single fresh
  initial commit on a `publish` branch, and push only that branch. Deleting/never-pushing the
  current `main` is safe because nothing external references those SHAs yet.

## Runtime privacy notes

- **Logcat:** one leak — `SmsSender.kt:42` (`Log.d` with recipient number + full SMS body incl.
  coordinates; SR-2). `SmsSender.kt:45` (`Log.e` with exception only) and the rest of the
  codebase contain no other `Log.d/e/i/w/v` or `println`/`System.out` calls that emit PII
  (verified by `git grep -nI -E 'Log\.(d|e|i|w|v)|println|System\.out'`: these two lines are
  the only hits in production code).
- **Backup:** `allowBackup="true"` with no data-extraction rules; recipients SharedPreferences
  are backup-eligible (SR-3).
- **Permissions:** declared set is `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `SEND_SMS`
  (all functionally required) plus `INTERNET` which is never used (SR-4). No `READ_*`, contacts,
  camera, or background-location permissions.
- **ProGuard:** `app/proguard-rules.pro` contains only the two dead `-keep` rules for
  `com.google.android.gms.**` (SR-5); release builds run with `isMinifyEnabled = true` and the
  standard optimize baseline. Release signing is not configured in-repo (`debug` builds use the
  debug signing config only), so no signing secrets are present or needed in the tree.
- **Storage:** recipients are a plain CSV in app-private SharedPreferences (`Settings.kt`);
  fine for the app's scope — the exposure paths are backup (SR-3) and logcat (SR-2), not the
  store itself.

## Explicitly checked, nothing found

Categories searched in tracked content at HEAD (`git grep -nI`), in the full history
(`git log --all -p`), and in the working tree, with no findings beyond those tabled above:

1. **Private keys / SSH keys / certificates** — no `BEGIN … PRIVATE`, `*.pem`, `*.jks`,
   `*.keystore`, `*.p12`, `*.pk8`, `id_rsa` anywhere in HEAD or history (the only `*.pem` hit
   pattern-wise is the `.gitignore` rule itself).
2. **API keys, tokens, passwords, OAuth/client secrets, cloud credentials** — the only hit for
   `secret|password|token|api[_-]key|credential|oauth|access[_-]?key` in all of history is the
   `.gitignore` comment line `# --- Keystores & signing secrets (NEVER commit) ---`.
3. **Email addresses** — none (only XML namespace URIs such as `schemas.android.com`); zero
   matches in HEAD and history.
4. **Real phone numbers** — only the documented placeholder `+1234567890` in `README.md`,
   `strings.xml`, `activity_main.xml`, and a KDoc comment in `SmsSender.kt`. No real numbers.
5. **Recipient lists / contact data** — no recipient CSV, preferences file, or `.ab` backup in
   HEAD, history, or working tree.
6. **Real personal names** — no person names in strings, layouts, comments, or commit
   messages; the local username (redacted as `<user>` above) appears only inside the SR-1 path (and its redacted cousins
   in ignored working-tree files, SR-7).
7. **Home/work postal addresses** — none found in any category.
8. **Hardcoded GPS coordinates** — no lat/lng decimal pairs anywhere (regex
   `[0-9]{1,3}\.[0-9]{4,},\s*-?[0-9]{1,3}\.[0-9]{4,}` over HEAD and history: zero hits; the
   README uses `<lat>,<lng>` placeholders only).
9. **Absolute paths containing a username** — exactly one in HEAD/history: `README.md:66`
   (SR-1). No `/Users/…`, `C:\…`, or other home-dir references. `build.sh` uses only portable
   `${HOME}` expansions.
10. **Gradle configuration** — no embedded credentials, repository auth, or signing configs in
    `build.gradle.kts`, `app/build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`;
    `gradle/wrapper/gradle-wrapper.properties` points at the standard official Gradle
    distribution.
11. **.gitignore for the obvious hazards** — `local.properties`, all build output
    (`.gradle/`, `build/`, `app/build/`), IDE dirs, and every keystore/signing filename class
    (`*.jks`, `*.keystore`, `*.p12`, `*.pk8`, `*.pem`, `keystore.properties`,
    `signing.properties`) are confirmed matched by `git check-ignore -v`; gaps are limited to
    the additional classes in SR-6.
12. **Untracked sensitive files in the working tree** — `git status --ignored` lists only
    `.gradle/`, `app/build/`, `local.properties`, `CHECKPOINT.md`, `WP1.md`, `WP2.md`; all are
    correctly ignored, and the contents of each were reviewed (see SR-7). No other stray files
    exist outside ignored build directories.

WP2-DONE
