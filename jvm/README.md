# JVM extension artifacts

Royal Road is the first dual-target source. The JVM build compiles its unchanged
`src/main/java` source tree into a thin Java 11 JAR. The normal Android project
builds the APK. API and host libraries are supplied by the host process.

## Building and verifying

Use JDK 21, Python 3 and Android SDK 36 with build-tools. Set `ANDROID_HOME`.
Until the core package is published, pass a local API checkout containing `core/`:

```sh
python scripts/build_dual.py --api /path/to/shared-runtime-api
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
./gradlew -p jvm :royalroad:test :royalroad:jar \
  -PgrimoireApiDir=/path/to/shared-runtime-api --no-build-cache
```

Without the composite, `extensions-core:1.3.0-SNAPSHOT` resolves through GitHub
Packages using `GITHUB_ACTOR` and `GITHUB_TOKEN`. `extensionsCoreVersion` overrides
that dependency. The normal Android catalog stays pinned to its existing API.
The optional root composite substitutes the wrapper and core only for local work.

## Format 1 metadata

The JAR manifest declares `Grimoire-Extension-Format: 1`,
`Grimoire-Core-Api: 1.3`, `Grimoire-Source-Package`, `Grimoire-Source-Class`,
`Grimoire-Version-Code`, `Grimoire-Version-Name` and
`Grimoire-Source-Version-Code`. `Grimoire-Host-Libraries` identifies the required
`org.json:json:20240303` JVM host dependency. Kotlin, OkHttp, Jsoup, coroutines and
the core API also come from the host. The JAR bundles none of those libraries.
This format currently admits portable sources only; a browser-dependent source
must gain an explicit host browser capability before joining this build.

`src/en/royalroad/extension.properties` mirrors the existing Android package,
entry point and artifact versions. Keep it in step with Android build declarations
and `@SourceInfo` on future behavior changes. The build gate rejects drift. The
artifact versionCode (4) and annotation versionCode (5) were already distinct;
both are preserved. Source ID uses the same package hash on both targets.

## Runtime parity and remaining work

The packaged JAR test loads classes with a separate URLClassLoader and shares the
host API. The common fixture contract covers browse/latest/search/details,
chapter ordering/locks, rich content and hidden-text removal. Its result includes
the exact JAR hash. `contract/` is test-only and is never packaged in extensions.

Native APK parity was validated with a local Android instrumentation harness
that loads the APK with DexClassLoader and compares the same contract and
artifact hashes. That prototype harness remains local; this change ships the
maintained JVM contract and packaging tools. A reusable Android CI harness is
still needed. A successful APK build alone is not native parity.
`artifacts.json` records that the packaging command does not run instrumentation.

Only Royal Road is enabled. Shared extension bases containing Android code,
browser capabilities, signed JAR distribution/trust, release indexes, reusable
`extensions-ci` integration and publication remain later work. See
[local validation](validation.md) for the completed proof.
