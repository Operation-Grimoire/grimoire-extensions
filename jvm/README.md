# JVM extension artifacts

Each extension keeps one directory, `src/<language>/<name>`. The Android build
and the JVM build both walk those directories. The JVM build compiles each
`src/main/java` tree into a thin Java 11 JAR. API and host libraries are supplied
by the host process.

## Building and verifying

Use JDK 21, Python 3 and Android SDK 36 with build-tools. Set `ANDROID_HOME` and
GitHub Packages credentials (`GITHUB_ACTOR`, `GITHUB_TOKEN` with package-read access).
The normal build consumes the configured published API/core version:

```sh
python scripts/build_dual.py
python -m unittest discover -s scripts/tests -v
```

The script assembles every debug APK, builds every JAR, and runs the Royal Road
JVM contract. It validates APK/JAR identity with `aapt2`, rejects bundled API,
foreign classes, and Android references, and writes both artifacts plus SHA-256
hashes under `build/dual/<module>/`.
`--variant release` builds the existing unsigned release variant. It does not
sign, publish or change the release index. `--verify-only` checks existing output
against the current Android declarations: `applicationId`, `versionCode`,
`versionName`, the manifest class, and the `@SourceInfo` versionCode. The
generated `identity.json` is accepted only when it matches those declarations.

To build only the JVM target:

```sh
./gradlew -p jvm test jars --no-build-cache
```

`python scripts/build_dual.py --jars-only` checks those JARs without assembling APKs.
The default command assembles every extension APK, builds every JAR, and checks that
each pair names the same package, class, and version.

Android and JVM builds share the stable `1.6.0` API version from
`gradle/libs.versions.toml`. `extensionsCoreVersion` can override the JVM core for
development. A local API checkout is optional: pass `--api` to the dual-build
script or `-PgrimoireApiDir` to Gradle to substitute it for the published package.

The JVM artifacts workflow runs the Python rejection gates, all Royal Road parser
tests and both packaged-JAR contracts on every PR and main push. It uses the
published core, with no API checkout or composite, and uploads the JAR, contract
snapshot and test XML. The JVM-only job needs no Android build. APK preview/release
workflows remain separate; a passing JVM job is not Android/browser parity.
Test reports are retained even when tests fail. JAR/contract artifacts are
uploaded only after a successful build.

## Format 1 metadata

The JAR manifest declares `Grimoire-Extension-Format: 1`,
`Grimoire-Core-Api: <major.minor>`, `Grimoire-Source-Package`, `Grimoire-Source-Class`,
`Grimoire-Version-Code`, `Grimoire-Version-Name` and
`Grimoire-Source-Version-Code`. `Grimoire-Host-Libraries` identifies the required
`org.json:json:20240303` JVM host dependency. Kotlin, OkHttp, Jsoup, coroutines and
the core API also come from the host. The JAR bundles none of those libraries.
GalaxyNovels asks the host to render a page through `SourceBrowser`. A desktop
host still implements `SourceDocumentBrowser` before those pages render. Sources
that use `io.grimoire.extensions.lib` also need the host JAR written to
`jvm/build/host`.

The JVM build reads each extension's `applicationId`, `versionCode`, and
`versionName` from its Android `build.gradle.kts`, the entry point from
`AndroidManifest.xml`, and the source `versionCode` from `@SourceInfo`. The
artifact version and the annotation version stay independent. Royal Road keeps
artifact versionCode 5 and annotation versionCode 6. Source ID uses the same
package hash on both targets.

The Android shared library generates the `grimoire.extension.minApi` manifest
value from its `minimumApi` compatibility floor (currently 1.5), independently of
the newer compile-time catalog pin. An extension using a newer API sets
`minimumApi` in `defaultConfig` and overrides that string resource; NovlTime
requires 1.6. The JVM build reads the same declaration for `Grimoire-Core-Api`.
The packaging verifier rejects missing or different APK/JAR requirements. Hosts
require the same API major and a sufficient minor before executing extension code.
The Android app retains legacy APKs with no declaration, while malformed
declarations are rejected. These declarations describe compatibility; signed JAR
trust/distribution remains separate work.

## Runtime parity and remaining work

The packaged JAR tests check generated identity against the current Android
declarations supplied by Gradle before loading Royal Road and GalaxyNovels with
separate URLClassLoaders and the host API. Android loads each installed APK with its own
DexClassLoader. Each source runs one shared fixture contract on both targets;
the test host compiles the fixtures, not the source implementations.

Royal Road covers browse/latest/search/details, chapter ordering/locks, rich
content and hidden-text removal. Galaxy covers browse/latest/search/details,
Arabic search normalization, paginated chapters, full chapter URL slugs, and
Arabic prose/rich text with unrelated controls excluded.

The fixtures live under `contracts/royalroad` and `contracts/galaxynovels`.
`contracts/shared` provides one process-wide HTTP platform, because SourceNetwork
allows one platform per process. Every fixture HTTP request is intercepted
before network access; unmatched hosts or paths fail.

Galaxy's browser fixture supplies synthetic observations. It checks an unavailable
browser before and after fallback, plain HTTP 403 responses without a challenge
header, response closure before browser reads, and a single browser attempt even
on timeout. Other HTTP errors and allowed 404s retain their existing behavior.
The fixture also checks waiting through a challenge, accepting content, retaining
browser mode only after success, and rejecting cross-origin, malformed and
server-error observations. It also verifies that the source asks for its browser
extraction script. This fixture does not execute that JavaScript or render a page
in WebView or JCEF. The PASS report labels it `synthetic source protocol`.

The `JVM extension artifacts` workflow builds both JARs, both APKs and the opt-in
`parity-host` Android consumer. A fresh API 35 emulator runs exactly two passing
instrumentation tests. CI rejects ignored/assumption-failure statuses and retains
both sources' reports and hashes. Detailed parser parity for the other sources
is not established by their artifact identity checks.

`prepare_parity.py --module <module>` verifies the selected source's
package/class/version/API metadata and binds its JVM report to the actual JAR.
Supported contract modules are `en-royalroad` (the default) and
`ar-galaxynovels`. `compare_contracts.py` requires identical source results,
identity, API requirements and both artifact hashes. A `parity-result.json` is
written per module only after the comparison succeeds. It retains the Android
API level, page hash and browser evidence scope. The standalone dual-build
command still records instrumentation as unexecuted because it does not run an
emulator.

To reproduce native parity, first build the JARs and JVM reports, then both APKs
and the test host:

```sh
bash ./gradlew -p jvm test jars
bash ./gradlew :en-royalroad:assembleDebug :ar-galaxynovels:assembleDebug \
  :parity-host:assembleDebug :parity-host:assembleDebugAndroidTest \
  -PincludeParityHost=true
```

Follow the workflow's staging commands, which place each source's exact inputs
under `build/parity/<module>/`, then run
`bash .github/scripts/android-parity.sh` on a disposable emulator.

Synthetic parser/protocol checks remain separate from real-site networking,
source login, browser challenges and faithful rendered text. Nightly beta
`1.0.5-beta.339+93edd5a` could upgrade and persist its Beta channel, but Galaxy's
live catalog returned HTTP 403 even after the public site opened in WebView.
Chapter reading remained unexecuted; see
[issue #138](https://github.com/Operation-Grimoire/grimoire-extensions/issues/138).
The new offline contract does not close that live acceptance issue.

Every extension under `src/<language>/<name>` still gets a thin JAR from that same
directory. No Android/JVM parser forks or generated source copies are introduced.
A maintained desktop host, real browser adapters and reusable `extensions-ci`
integration remain later work. See [local validation](validation.md) for the
earlier Royal Road proof.

## Signed JAR index

Every main-branch run of `release.yml` publishes the Desktop catalog next to
Android's `index.json` on the `latest` release, after the APK release succeeds.
The format is defined in the app's
[desktop extension contract](https://github.com/Operation-Grimoire/grimoire/blob/master/docs/desktop-extensions.md).

1. `jar-index-build` runs the script tests and `build_dual.py --jars-only` with
   a read-only token and the pinned API.
2. `jar-index-publish` runs in the `jar-index-publication` concurrency group.
   `scripts/jar_index.py recover` chooses the previous index (recovery below).
   `generate` writes `jar-index.json` (policy below). `sign` signs it with
   `openssl pkeyutl -sign -rawin` and the `JAR_INDEX_SIGNING_KEY` secret (PEM
   PKCS#8 Ed25519). `verify` then checks it against the pinned official public
   key `PYUsj2AvddEkNAiO8Vc2tPskScoilyAdbMG8ZNN/IfM=`. A different key fails the
   job.
3. Uploads run in this order: new artifacts, then the immutable pair
   `jar-index-<sequence>.json` and `jar-index-<sequence>.json.sig`, then
   `jar-index.json` and `jar-index.json.sig`. Only the canonical pair is ever
   replaced. Versioned pairs beyond the newest five are deleted afterwards.
   Two decisions are made separately:
   - Artifacts the index lists but the release lacks are uploaded, even when
     the index is otherwise unchanged.
   - A new index pair is published only when the index changed apart from its
     sequence, or when the canonical pair is not current.

   Nothing is published in these cases:
   - `main` has moved on;
   - the release already holds the current index and every artifact it lists;
   - the secret is unset. This only adds a notice and does not fail the Android
     release.

Recovery. `recover` verifies the canonical pair and every versioned pair with
the pinned key, then uses the verifying index with the highest sequence. A
versioned pair must also contain the sequence its file name states. When the
canonical pair is missing, mismatched or older, the job logs a warning, uses
that versioned pair, and republishes the canonical pair. A rerun after an
interrupted upload therefore repairs the release.

A first publication starts only when no pair and no canonical index exist. If
pairs exist but none verifies, the job fails. Index files without a signature
are ignored with a warning.

Desktop treats an equal `versionCode` with a different `sha256` as a conflict.
For that reason, published bytes never change under the same version:

- **Pinned version.** A package keeps its previous entry and its published JAR
  unchanged when both of these hold:
  - its `versionCode` equals the previous index's;
  - the release still holds that file with the recorded digest.

  The rebuilt JAR is not uploaded. If its bytes differ, the job adds a notice.
- **New version.** Only a higher `versionCode` publishes new bytes.
- **Lower version.** A `versionCode` below the published one fails the job.
- **Missing asset.** If the previous file is missing from the release, the
  rebuilt JAR is published instead.
- **Changed library.** If the shared library's rebuilt bytes differ from the
  published entry, every extension that lists its coordinate in
  `hostLibraries` must have a higher `versionCode`. Otherwise the job fails and
  names the extensions to bump. Each index lists one library per coordinate.
  The library file name is content-addressed, as
  `extensions-lib-<version>-<first 12 hex of sha256>.jar`. A changed library is
  therefore a new file, and older indexes keep pointing at their own bytes.
- **Name collision.** New bytes never replace a release asset of the same name
  with different bytes. A collision fails the job.

Field sources:

| Field | Source |
| --- | --- |
| `pkg`, `sourceClass`, `versionCode`, `versionName`, `sourceVersionCode`, `coreApi`, `hostLibraries` | The JAR's `Grimoire-*` manifest. Must equal `identity.json`. |
| `file`, `sha256`, `size` | The JAR bytes. Over 20 MiB is rejected. |
| `name`, `adultContent` | `@SourceInfo`, as in the Android index generator. |
| `lang` | A string `@SourceInfo` lang, otherwise the `src/<language>` directory, as in `index.json`. |
| `iconUrl` | The same package's `iconUrl` in the published `index.json`. Left out with a warning when absent. |
| `libraries` | Each `io.grimoire` host library coordinate, mapped to its JAR in `jvm/build/host`. The published `file` is content-addressed. `org.json` is supplied by the host. |
| `sequence` | Generation time in epoch seconds. Always above the previously published value. |

On `main`, `lib` still builds as `io.grimoire:extensions-lib:1.0.0-SNAPSHOT`.
Moving the JAR index to a released library coordinate is follow-up work.

To reproduce locally with a throwaway key:

```sh
./gradlew -p jvm jars
openssl genpkey -algorithm ed25519 -out /tmp/key.pem
python scripts/jar_index.py generate --output build/jar-index --android-index index.json
python scripts/jar_index.py sign --index build/jar-index/jar-index.json --key /tmp/key.pem
python scripts/jar_index.py verify --index build/jar-index/jar-index.json \
  --public-key "$(openssl pkey -in /tmp/key.pem -pubout -outform DER | tail -c 32 | base64)"
```
