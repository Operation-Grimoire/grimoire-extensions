# Unsupported / not-added sources

Sources that were evaluated for an extension but **not added**, with the reason.
Kept here so the same site isn't re-investigated from scratch, and so a request
for one of these has a documented answer.

Reasons fall into a few buckets:
- **Not prose** — a manga/image site, not text novels.
- **JS-rendered content** — the chapter text isn't in the server HTML (injected
  by client-side JS), so a scraper gets empty pages.
- **Custom platform** — no shared theme; needs a bespoke source (often reverse
  engineering a private JSON API).
- **Needs a shared base first** — a common theme we don't have a base for yet;
  tracked for a future batch rather than a one-off.
- **Blocked / paywalled** — requests time out, loop, or the content is behind a
  paid membership.

| Language | Source | URL | Reason not added |
|----------|--------|-----|------------------|
| ar | Hizo Manga | https://hizomanga.net | Manga (images), not prose; popular listing also redirect-loops under the app's client |
| ar | Novel Arab | https://novelarab.com | JS-rendered content — chapter prose is injected client-side, empty in the server HTML |
| ar | Markaz Riwayat | https://markazriwayat.com | Blocked/paywalled — listing requests time out |
| ar | kolnovel | https://kolnovel.com | Needs a shared base first — Themesia "LightNovel" theme (not yet supported) |
| ar | novelsparadise | https://novelsparadise.site | Themesia theme + membership paywall (locked chapters) |
| ar | rewayat.club | https://rewayat.club | Custom platform — Vue SPA backed by a private JSON API |
| ar | realmnovel | https://www.realmnovel.com | Custom platform — bespoke app with a Mongo-style API |
| ar | sunovels | https://sunovels.com | Custom platform — bespoke site, no shared theme |
| ar | rewayatfans | https://rewayatfans.com | Plain WordPress (no novel theme); novels are raw posts — bespoke scraping |
| ar | rewayahfans | https://rewayahfans.net | Plain WordPress (no novel theme); bespoke scraping (sister site of rewayatfans) |
