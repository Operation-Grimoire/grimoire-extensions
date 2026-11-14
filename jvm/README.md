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
tests and the packaged-JAR contract on every PR and main push. It uses the
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
artifact versionCode 4 and annotation versionCode 5. Source ID uses the same
package hash on both targets.

## Runtime parity and remaining work

The packaged JAR test loads classes with a separate URLClassLoader and shares the
host API. The common fixture contract covers browse/latest/search/details,
chapter ordering/locks, rich content and hidden-text removal. Its result includes
the exact JAR hash. The fixture lives in `jvm/src/test/kotlin` and is never packaged in extensions.
It supplies synthetic HTTP responses and expected results; the parser itself
comes from `src/en/royalroad`.

Native APK parity was validated with a local Android instrumentation harness
that loads the APK with DexClassLoader and compares the same contract and
artifact hashes. That prototype harness remains local; this change ships the
maintained JVM contract and packaging tools. A reusable Android CI harness is
still needed. A successful APK build alone is not native parity.
`artifacts.json` records that the packaging command does not run instrumentation.

Every extension under `src/<language>/<name>` gets a thin JAR from that same
directory. Only Royal Road has the fixture contract on top of the identity check.
Signed JAR distribution,
release indexes, reusable `extensions-ci` integration, and publication remain
later work. See [local validation](validation.md) for the completed Royal Road proof.
