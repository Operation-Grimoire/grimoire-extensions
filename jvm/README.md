# JVM extension artifacts

Each extension keeps one directory, `src/<language>/<name>`. The Android build
and the JVM build both walk those directories. The JVM build compiles each
`src/main/java` tree into a thin Java 11 JAR. API and host libraries are supplied
by the host process.

## Building and verifying

Use JDK 21, Python 3 and Android SDK 36 with build-tools. Set `ANDROID_HOME` and
GitHub Packages credentials (`GITHUB_ACTOR`, `GITHUB_TOKEN` with package-read access).
The normal build consumes the published API/core release:

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

Android and JVM builds share the stable `1.5.0` API version from
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
`Grimoire-Core-Api: 1.5`, `Grimoire-Source-Package`, `Grimoire-Source-Class`,
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
value from the same catalog pin. It is a string resource containing `major.minor`,
matching the JAR's `Grimoire-Core-Api`. The packaging verifier rejects missing or
different APK/JAR requirements. Hosts require the same API major and a sufficient
minor before executing extension code. The Android app retains legacy APKs with
no declaration, while malformed declarations are rejected. These declarations
describe compatibility; signed JAR trust/distribution remains separate work.

## Runtime parity and remaining work

The packaged JAR tests load Royal Road and GalaxyNovels with separate
URLClassLoaders and the host API. Android loads each installed APK with its own
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
browser before and after fallback, waiting through a challenge, accepting content
and an allowed 404, retaining browser mode, and rejecting cross-origin, malformed
and server-error responses. It also verifies that the source asks for its browser
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

To reproduce native parity, build both extensions and the test host:

```sh
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
A maintained desktop host, real browser adapters, signed JAR distribution,
release indexes, reusable `extensions-ci` integration and publication remain
later work. See [local validation](validation.md) for the earlier Royal Road proof.
