# Dual artifact validation

Local Windows/JDK 21 evidence, 2026-11-08 (2026-11-09 UTC).
Extensions base: `c602223b8e48b137098fea9eff62d63f0140cc66`.

- Existing Royal Road APK/parser-test baseline passed against the default API.
- Opt-in shared API composite built the debug APK and passed all 15 parser tests.
- JVM passed those same 15 parser tests plus one packaged-JAR contract test.
- Two Python gate tests passed, covering incompatible metadata, bundled API,
  changed content, stale artifact hashes and wrong identity/version.
- Package/class/artifact versions matched across the APK, JAR and descriptor.
- The Java 11 JAR contains only Royal Road classes and no Android references.
- Real Android API 35 instrumentation loaded the APK with DexClassLoader,
  shared the host Source class and passed the same deterministic fixture contract.
  `compare_contracts.py` matched browse/latest/search/details/chapters/content
  and tied the comparison to both artifact hashes.

The common source ID is `1274264049194160859`. The formatted page snapshot SHA-256
is `fd64f7188d5d4403b43072dc3af53e64fe0c70bc9f9d50e291e2202ecec60fca`.
Artifact hashes are generated in `build/dual/royalroad/artifacts.json`; native and
JVM result files carry their corresponding hashes to reject stale proof.

Grimoire's desktop fixture/UI test suite also passed while loading this external
format-1 JAR. Inspector's migrated host discovered all 26 current source classes,
passed its offline suite and completed live Royal Road with zero errors and
two missing-thumbnail warnings.

No remote CI, publication, signing, release-index edits, authenticated source
login, real browser challenge solve or other-source JVM artifacts were executed.
Existing Android release declarations, catalog pins and reusable workflows remain
unchanged. Native fixture parity is not evidence of universal site/browser parity.

The Android artifact harness and desktop/session prototypes remain local and are
excluded from the checkpoint PRs. JVM parser/contract tests and packaging gates
are committed here; the native result above is recorded local evidence, not an
Android CI job that this PR adds. Existing remote APK preview checks do not cover
the new JVM artifact path.
