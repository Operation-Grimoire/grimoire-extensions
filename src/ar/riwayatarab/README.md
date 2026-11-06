# Riwayatarab

Arabic prose source for [Riwayatarab](https://riwayatarab.com), requested in
[issue #129](https://github.com/Operation-Grimoire/grimoire-extensions/issues/129).
Reading does not require an account.

The extension uses `https://api.riwayatarab.com/api/novels` for Popular
(`sort=views`), Latest (`sort=updated_at`), search (`search`), and novel details.
Listings use 24 records per page. Chapter lists use
`/api/novels/{slug}/chapters?page=N&limit=50` and expose the total page count.
Incomplete pages and duplicate chapter URLs raise errors.

Chapter text comes from `.chapter-content` on the public website. Both `<br>`
breaks and paragraph boundaries are retained in plain text and rich HTML.
Each chapter is returned as one text block so inline formatting can span breaks.
The launcher icon is the PNG embedded in the site's favicon.

Some titles have several records, including records without chapters. The
extension preserves each distinct URL. Some cover URLs supplied by the site
point to missing RealmNovel images; these remain site limitations. The site's
unspecified author (`غير محدد`) is represented as an absent author.

## Validation

Checked on November 6, 2026:

- Ten unit tests passed for request parameters, pagination, metadata, Arabic
  and encoded slugs, chapter identity, content extraction, and error handling.
- Debug APK assembly and Android lint passed. Lint reported zero errors and
  three warnings about the repository's SDK and backup settings.
- Extension Inspector's live suite passed with zero errors and three warnings:
  failed cover loads in Popular and Latest, and the unspecified author.
  The suite loaded all 3,608 chapters of *I Can Copy Talents* and its first chapter.
- Additional Inspector API checks verified distinct Popular pages 1 and 2,
  the final 18 records on page 37, an empty page 38, Arabic search, and all
  2,558 unique chapters of *Shadow Slave*.
- Extracted text matched the server HTML after whitespace normalization for
  *Shadow Slave* chapters 1 and 2,558, and *Reverend Insanity* chapter 100.

Inspector runs source code on the JVM. Android device and offline-reader checks
were not run.

From the extensions repository, run:

```powershell
.\gradlew.bat :ar-riwayatarab:testDebugUnitTest :ar-riwayatarab:assembleDebug :ar-riwayatarab:lintDebug
```

From an Extension Inspector checkout with the current Android stubs, run:

```powershell
.\gradlew.bat run -Pext=PATH_TO_EXTENSIONS -Papi=PATH_TO_API --args="run --source riwayatarab --query Guiltythree --timeout 120 --concurrency 4 --json"
```

The ASCII author query avoids Windows launcher argument-encoding problems;
Arabic search was checked separately through Inspector's JSON API.
