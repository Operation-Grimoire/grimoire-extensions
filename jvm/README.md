# JVM extension artifacts

Royal Road is the first dual-target source. The JVM build compiles its unchanged
`src/main/java` source tree into a thin Java 11 JAR. The normal Android project
builds the APK. API and host libraries are supplied by the host process.

## Building and verifying

Use JDK 21, Python 3 and Android SDK 36 with build-tools. Set `ANDROID_HOME` and
GitHub Packages credentials (`GITHUB_ACTOR`, `GITHUB_TOKEN` with package-read access).
The normal build consumes the published API/core release:

```sh
python scripts/build_dual.py
python -m unittest discover -s scripts/tests -v
```

The script sequentially builds the debug APK, runs Android-side parser unit
tests, builds the JAR and runs JVM parser/artifact tests. It validates APK/JAR
identity with `aapt2`, rejects bundled API/foreign classes and Android references,
and writes both artifacts plus SHA-256 hashes to `build/dual/royalroad/`.
`--variant release` builds the existing unsigned release variant. It does not
sign, publish or change the release index. `--verify-only` checks existing output.

To build only the JVM target:

```sh
./gradlew -p jvm :royalroad:test :royalroad:jar --no-build-cache
```

Android and JVM builds share the `1.4.1-SNAPSHOT` API version from
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
`Grimoire-Core-Api: 1.4`, `Grimoire-Source-Package`, `Grimoire-Source-Class`,
`Grimoire-Version-Code`, `Grimoire-Version-Name` and
`Grimoire-Source-Version-Code`. `Grimoire-Host-Libraries` identifies the required
`org.json:json:20240303` JVM host dependency. Kotlin, OkHttp, Jsoup, coroutines and
the core API also come from the host. The JAR bundles none of those libraries.
GalaxyNovels asks the host to render a page through `SourceBrowser`. That source
compiles in the portable JVM check. A desktop host still implements
`SourceDocumentBrowser` before those pages render.

`src/en/royalroad/extension.properties` mirrors the existing Android package,
entry point and artifact versions. Keep it in step with Android build declarations
and `@SourceInfo` on future behavior changes. The build gate rejects drift. The
artifact versionCode (4) and annotation versionCode (5) were already distinct;
both are preserved. Source ID uses the same package hash on both targets.

## Runtime parity and remaining work

The packaged JAR test loads classes with a separate URLClassLoader and shares the
host API. The common fixture contract covers browse/latest/search/details,
chapter ordering/locks, rich content and hidden-text removal. Its result includes
the exact JAR hash. The fixture lives in `royalroad/src/test/kotlin` and is never
packaged in extensions. It supplies synthetic HTTP responses and expected results;
the parser itself comes from the existing extension source tree.

Native APK parity was validated with a local Android instrumentation harness
that loads the APK with DexClassLoader and compares the same contract and
artifact hashes. That prototype harness remains local; this change ships the
maintained JVM contract and packaging tools. A reusable Android CI harness is
still needed. A successful APK build alone is not native parity.
`artifacts.json` records that the packaging command does not run instrumentation.

Only Royal Road has a verified APK/JAR contract. The `portable` module compiles
the shared library plus WebNovel, Azure Chronicles, Z-Library, and GalaxyNovels
against the host cookie and document-browser interfaces. Signed JAR distribution,
release indexes, reusable `extensions-ci` integration, and publication remain
later work. See [local validation](validation.md) for the completed Royal Road proof.
