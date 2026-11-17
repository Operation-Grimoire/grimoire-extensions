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

## NovelBuddy timeout recovery (2026-11-17 UTC)

NovelBuddy 1.0.13 keeps one implementation for its APK and Java 11 JAR. When
server-rendered `initialManga` is absent, details and chapters make one request to
the public by-slug details endpoint used by the site's client. A failed fallback
raises a source error; it cannot initialize a fake novel or hide a chapter error
as an empty list. Valid server-rendered data avoids the fallback request.

Eight deterministic cases run in both Android unit tests and the JVM suite.
They cover normal SSR, details/chapter recovery, exact chapter paths/order,
unavailable/partial payloads, HTTP failures, malformed fallback JSON with its
cause retained, and missing page data. The failures were reproduced before the
corresponding fixes. Local JVM reports contain 27 passing tests; Android reports
contain eight, with no failures or skips. APK assembly and lint pass. The dual
artifact validator checks matching version/class/minimum API, Java 11 and no
Android references in the JAR.

The live Inspector run against published core 1.6.0 passed popular/latest/search
(24 items each), details, 3,956 chapters, 90 prose blocks and dynamic filters,
with zero errors or warnings. The site's public details API was independently
verified while its SSR page returned null with a timeout message; later SSR
requests recovered too. Fixtures prove the fallback branch deterministically;
Inspector is JVM/live-source evidence, not Android device or native APK parity.
Automatic source publication and exact-head CI are recorded in issue #144/its PR.
