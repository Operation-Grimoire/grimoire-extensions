# NovlTime

Arabic prose source for [NovlTime](https://novltime.com/ar), requested in
[issue #133](https://github.com/Operation-Grimoire/grimoire-extensions/issues/133).
Reading does not require an account.

The extension uses the site's public `/api/v1` endpoints for Popular, Latest,
search, metadata, and chapter lists. Popular and search reconstruct the API's
opaque cursor chain when a later page is requested. Latest mirrors the finite
recent-release feed rather than the separate newest-additions archive. Requests
use a desktop-Chrome user agent because the API returns gateway errors for the
mobile-Chrome user agent used by default on Android and in Inspector.

Chapter lists implement `CursorPaginatedSource` with 200-entry pages. Grimoire's
shared cursor walker follows continuations and rejects blank/repeated cursors,
empty continuations, and excessive walks instead of returning a truncated book.
Idempotent requests use up to five total attempts with bounded backoff for
transient gateway failures. Chapter numbers may be zero or fractional. Premium
or hidden chapters are marked locked. Chapter reading parses `.chapter-body`
and retains DOM order, inline formatting, and images while excluding controls
and scripts.

The launcher icon is derived from the site's public `/icons/icon-512.png`.

## Validation

Checked on November 15, 2026:

- Ten unit tests passed. Debug APK assembly and Android lint passed with zero
  errors and four warnings about SDK versions, backup rules, and icon shape.
- JVM tests and JAR assembly passed. Dual-artifact checks verified all 26 JARs
  and the Royal Road contract; all ten Python packaging tests passed.
- Inspector's cursor-aware live suite passed with zero errors and zero warnings.
  It returned 20 Popular novels, 20 Latest novels, two search results, all 2,321
  chapters of *Reverend Insanity*, and 42 text blocks from its first chapter.
- Inspector's cursor endpoint returned 200 entries on its first page and another
  200 on its second, covering chapter numbers 0 through 399 without a full walk.

The CLI uses the ASCII query `world` because the Windows launcher corrupts
Unicode arguments. Arabic search was checked separately through Inspector's
UTF-8 JSON API. Inspector runs source code on the JVM; Android device and
offline-reader checks were not run.

From the extensions repository:

```powershell
.\gradlew.bat :ar-novltime:testDebugUnitTest :ar-novltime:assembleDebug :ar-novltime:lintDebug
bash ./gradlew -p jvm test jars --no-build-cache
python scripts/build_dual.py --jars-only
```

From an Extension Inspector checkout:

```powershell
.\gradlew.bat run -Pext=PATH_TO_EXTENSIONS -Papi=PATH_TO_API --args="run --source novltime --query world --timeout 180 --json"
```
