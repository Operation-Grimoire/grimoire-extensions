# Grimoire Extensions

Content-source extensions for the Grimoire reader. Each extension builds to two
artifacts from one source tree:

- an **Android APK** that the app loads dynamically and offers through its
  extension browser, backed by a generated `index.json`;
- a thin **Java 11 JAR** for the Desktop host, listed in a signed
  `jar-index.json` (see `jvm/README.md`).

## Layout

- `src/{lang}/{name}/` — one extension per directory (e.g. `src/en/novelfull/`).
  `settings.gradle.kts` auto-includes any such dir with a `build.gradle.kts` as
  module `:{lang}-{name}`. The JVM build (`jvm/`) and Extension Inspector
  discover extensions the same way; nothing else needs registering. `all` is
  used for multi-language sources.
- `lib/` — shared base classes for common site themes: `WPNovelsSource`,
  `NovelFullThemeSource`, `WpComNovelPagesSource`, `PatreonSource`. Extensions
  in this repo consume it as `project(":lib")`; it is also published for
  sibling repos (see "Publishing `lib/`").
- `jvm/` — the JVM/Desktop build: thin JARs, packaged-JAR contract tests.
- `contracts/` — shared fixture contracts run on both targets (Royal Road,
  GalaxyNovels). `parity-host/` is an opt-in Android test consumer for them.
- `scripts/` — `build_dual.py` (APK + JAR identity/packaging gates),
  `jar_index.py` (Desktop index), parity helpers, and their tests.
- `docs/UNSUPPORTED_SOURCES.md` — sites evaluated and not added, with reasons.
  Check it before investigating a requested site; add to it when declining one.
- Model/network types come from `io.grimoire:extensions-api` (Android) and
  `io.grimoire:extensions-core` (JVM), pinned by `extensionsApi` in
  `gradle/libs.versions.toml`. Both resolve from GitHub Packages, so builds need
  `GITHUB_ACTOR`/`GITHUB_TOKEN` with package-read access unless already cached.
  To develop against a local API checkout, pass `-PgrimoireApiDir=<path>`.

## Adding an extension

Follow the agent-neutral skill at `.agents/skills/add-extension/SKILL.md`:
site investigation, scaffold, implementation rules, tests, APK/JAR builds and a
live Extension Inspector run.

## API compatibility floor

`lib/` declares the shared minimum API (`minimumApi`, currently 1.5), which
every extension inherits. An extension that uses a newer API (e.g.
`SourceBrowser`, 1.6) must set `minimumApi` in its `defaultConfig` and override
the `grimoire_extension_min_api` string resource with that value (see
`src/ar/linovels`). The JVM build reads the same declaration for the JAR's
`Grimoire-Core-Api` manifest attribute, and the packaging gates reject
mismatches between the APK and JAR.

## Versioning — bump on every change to an extension

When you change an extension's behaviour (parsing, fetching — anything that
should ship), you MUST bump its version. Each extension has two independent
version fields:

1. **`build.gradle.kts` → `versionCode` + `versionName`** — the critical one.
   The release generator reads these into `index.json` (and the JAR build into
   the JAR manifest), so this is what the app uses to detect and offer updates.
   It is also the APK `versionCode`, which Android requires to increase for an
   install over an existing one. Skip this and the change never reaches users.
2. **`@SourceInfo(versionCode = N)`** in the extension's Kotlin source — the
   source's declared version. Keep it bumped in step.

Rules:

- The two counters are independent and usually differ (e.g. `novelfull`'s
  gradle and `@SourceInfo` values are not equal). Increment each by 1 from its
  own current value.
- `versionName` always follows `1.0.(versionCode - 1)` — gradle `versionCode` 4
  means `versionName` `"1.0.3"`.
- A new extension starts at gradle `versionCode = 1` / `"1.0.0"` and
  `@SourceInfo(versionCode = 1)`.
- Changing a shared base class in `lib/` affects every subclass: bump the
  version of every extension that subclasses it, or the change won't ship to
  them.

## Building and testing

```sh
./gradlew :{lang}-{name}:testDebugUnitTest :{lang}-{name}:assembleDebug
./gradlew -p jvm test jars              # JARs in jvm/build/extensions/{lang}-{name}/
python scripts/build_dual.py            # all APKs + JARs + identity gates (--jars-only to skip APKs)
python -m unittest discover -s scripts/tests -v
```

Requires JDK 21, Android SDK 36 (`ANDROID_HOME`) and Python 3. Live
verification uses [Extension Inspector](https://github.com/Operation-Grimoire/grimoire-extensions-inspector);
see the add-extension skill for the commands.

## Publishing `lib/`

`lib/` is published to GitHub Packages as `io.grimoire:extensions-lib` so
sibling extension repos (e.g. the private R18 repo) can consume it as a Maven
dependency. The contract mirrors `grimoire-extensions-api`: a `lib-vX.Y.Z` git
tag publishes the immutable release; every other build publishes
`<next>-SNAPSHOT`.

The published version is computed in `lib/build.gradle.kts` (`publishVersion`):

- No `LIB_RELEASE_TAG` env → `<base>-SNAPSHOT`. Every push to `main` publishes
  this mutable SNAPSHOT, for developing sibling repos against unreleased
  changes.
- `LIB_RELEASE_TAG` set → the concrete `X.Y.Z`. The **Publish lib/ to GitHub
  Packages** workflow sets it from a pushed `lib-vX.Y.Z` git tag.

### SemVer rules

Sibling repos build their extensions against this artifact, so the version
signals what they need to do on bump:

- **PATCH** — bug fix in an existing base class, no API surface change.
- **MINOR** — additive (new helper, new method on a base class). Existing
  subclasses keep compiling untouched.
- **MAJOR** — removed/renamed/retyped API on a shared base class, or any
  behavior change that requires subclasses to update. Sibling repos must
  rebuild their extensions and bump each extension's `versionCode`.

A `lib/` change does NOT need to bump this repo's extensions' versions for
*publishing* — they consume `project(":lib")` and rebuild from source. The
bump-every-subclass rule above still applies for *behavioral* changes that
should ship to users.

### To cut a release

1. Push a `lib-vX.Y.Z` tag on `main`:
   `git tag -a lib-vX.Y.Z <main-sha> -m "Release lib X.Y.Z" && git push origin lib-vX.Y.Z`
   This triggers the Publish workflow → immutable
   `io.grimoire:extensions-lib:X.Y.Z` on GitHub Packages.
2. Bump the `-SNAPSHOT` base (`publishVersion` in `lib/build.gradle.kts`) to
   the next version.
3. Pin sibling repos to the new concrete version in their
   `gradle/libs.versions.toml`.

## CI

- **`pr-build.yml` / `release.yml` / `pr-cleanup.yml`** are thin stubs that
  delegate to reusable workflows in
  [`operation-grimoire/extensions-ci`](https://github.com/Operation-Grimoire/extensions-ci),
  pinned to `@v2`. PRs get preview APKs for changed extensions; pushes to
  `main` build changed extensions and update the `latest` release and
  `index.json`, which carries each source's `adultContent` rating and
  `novelUpdatesGroups` from `@SourceInfo`. The change-detection and
  index-generation scripts live in extensions-ci. To change behavior shared by
  all sibling repos, edit extensions-ci and move the `v2` tag (or cut a new
  `v2.x.y`); to change repo-specific behavior, edit the stubs here.
- **`release.yml`** also has repo-specific jobs that publish the signed Desktop
  `jar-index.json` and thin JARs to the same `latest` release
  (`scripts/jar_index.py`, documented in `jvm/README.md`). They skip with a
  notice when the `JAR_INDEX_SIGNING_KEY` secret is unset.
- **`jvm.yml`** (PRs and `main`) runs the Python packaging gates, builds all
  JARs, runs the JVM contract tests, and compares Royal Road and GalaxyNovels
  results between the JAR and the APK on an Android emulator.
- **`publish-lib.yml`** publishes `lib/` (see above).
