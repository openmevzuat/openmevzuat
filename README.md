# OpenMevzuat

Versioned Turkish legislation from official public sources.

OpenMevzuat stores legislation as human-readable, article-level Markdown files with EDN metadata.

## Türkçe Açıklama

OpenMevzuat, Türkiye mevzuatını resmi kamu kaynaklarından çekip madde bazlı, okunabilir ve Git ile versiyonlanabilir dosyalar halinde saklayan açık kaynak bir projedir.

Bu proje resmi kaynak değildir ve hukuki tavsiye sunmaz. Nihai kaynaklar mevzuat.gov.tr, Resmî Gazete ve Türkiye Büyük Millet Meclisi gibi resmi kurumlardır.

## Status

Experimental. Not an official source.

## Scope

OpenMevzuat focuses on Turkish legislation.

It does not aim to become a multi-country legal data platform.

Supported document types:

- `constitution`
- `law`
- `decree`

Supported decree subtypes:

- `khk` — Kanun Hükmünde Kararname
- `cbk` — Cumhurbaşkanlığı Kararnamesi

Regulations are not in scope yet.

**Türkçe:** OpenMevzuat yalnızca Türkiye mevzuatına odaklanır. Anayasa, kanunlar, Kanun Hükmünde Kararnameler ve Cumhurbaşkanlığı Kararnameleri kapsam içindedir. Yönetmelikler henüz kapsamda değildir. Çok ülkeli bir hukuk veri platformu olmayı hedeflemez.

## Goals

- Track legislation changes over time
- Keep canonical text human-readable
- Make Git diffs useful
- Link every document to official sources

## Non-goals

- Legal advice
- Political scoring
- Person profiling
- Automatic legal interpretation
- Web UI, database, API, auth, billing, or queue infrastructure

## Data layout

Canonical source of truth:

```text
data/canonical/**/README.md
data/canonical/**/articles/*.md
data/metadata/**/*.edn
```

Derived files can be deleted and rebuilt:

```text
derived/full-text/**/*.md
derived/search/*.jsonl
derived/diffs/**/*.edn
```

Document folders are grouped by type:

```text
data/canonical/constitution/
data/canonical/laws/
data/canonical/decrees/

data/metadata/constitution/
data/metadata/laws/
data/metadata/decrees/
```

## Document IDs and Slugs

Document IDs:

```text
constitution/1982
law/{number}
decree/khk-{number}
decree/cbk-{number}
```

Decree slugs:

```text
khk-{number}-{slugified-title}
cbk-{number}-{slugified-title}
```

Example:

```clojure
{:document/id "decree/cbk-1"
 :document/type :decree
 :decree/subtype :cbk
 :document/number "1"
 :document/title "Cumhurbaşkanlığı Teşkilatı Hakkında Cumhurbaşkanlığı Kararnamesi"
 :document/slug "cbk-1-cumhurbaskanligi-teskilati-hakkinda-cumhurbaskanligi-kararnamesi"
 :document/path "data/canonical/decrees/cbk-1-cumhurbaskanligi-teskilati-hakkinda-cumhurbaskanligi-kararnamesi"}
```

## CLI

```bash
clojure -M:openmevzuat sync-catalog
clojure -M:openmevzuat update
clojure -M:openmevzuat update-configured
clojure -M:openmevzuat update-laws 193 2918
clojure -M:openmevzuat update-all-laws
clojure -M:openmevzuat update-all-laws --resume-from 702
clojure -M:openmevzuat build
clojure -M:openmevzuat clean-derived
```

`update` is the normal daily flow:

1. refresh the official active Kanunlar catalog in `data/catalog/laws.edn`;
2. query Resmî Gazete for recent Yasama/Kanun rows;
3. identify amendment laws that have not already been processed;
4. parse only those new amendment laws' `MADDE` introductions and extract the affected base kanun numbers;
5. resolve those numbers through the local catalog;
6. fetch and render only the affected consolidated kanun PDFs;
7. record successfully processed amendment laws in `data/state/resmigazete-amendments.edn`.

The default Resmî Gazete lookback window is 30 days. Override it with `OPENMEVZUAT_UPDATE_WINDOW_DAYS`. The window intentionally overlaps previous runs; `data/state/resmigazete-amendments.edn` prevents the updater from re-fetching and re-rendering kanuns for amendment laws already handled in an earlier automated update.

Fetch retries use exponential backoff plus a small random jitter. Override the jitter with `OPENMEVZUAT_FETCH_JITTER_MS`.

When `OPENMEVZUAT_PR_BODY_PATH` is set, `update` also writes a Markdown report for the automation PR body. The report lists the update window, detected/new/skipped Resmî Gazete amendment laws, affected kanuns, and the Resmî Gazete date/issue that triggered each affected kanun refresh.

The `Test` GitHub Actions workflow runs `clojure -M:test` on pull requests and manual dispatches. In a branch ruleset for `main`, select the required status check named `Clojure tests`.

The daily GitHub Actions workflow uses the generated report when it opens an automated update PR, then queues the PR for GitHub auto-merge. GitHub will merge it once the branch can be merged and any repository protection rules are satisfied. To make bot-created PRs trigger the `Clojure tests` pull request check, configure an `OPENMEVZUAT_BOT_TOKEN` repository secret with contents and pull request write access; otherwise the workflow falls back to `GITHUB_TOKEN`, which may not trigger follow-up workflows.

`sync-catalog` fetches the official active Kanunlar catalog from mevzuat.gov.tr and writes `data/catalog/laws.edn`. It does not fetch or render law PDFs.

`update-configured` fetches configured official sources from `resources/documents.edn`, normalizes text, parses articles, renders canonical files, writes EDN metadata, and regenerates derived outputs. `build` is an alias for this configured rebuild path.

`update-laws` incrementally fetches and renders only the requested law numbers or document IDs, then merges those rows into the search index and writes a selected-update manifest under `data/manifests/selected/`. For example, `update-laws 2918` updates `law/2918`; tertip collision IDs from the catalog can be addressed as `update-laws t5-3201` or `update-laws law/t5-3201`.

`update-all-laws` is the intentional full catalog rebuild path. It uses the synced catalog laws plus configured non-law documents, processes and writes one document at a time, and logs progress for each document. Reserve it for explicit full corpus refreshes or first-time backfills.

If a full backfill is interrupted, resume by progress index:

```bash
clojure -M:openmevzuat update-all-laws --resume-from 702
clojure -M:openmevzuat update-all-laws --resume-after 704
```

Resume mode seeds skipped documents from existing local metadata and canonical article files, then fetches only the remaining official PDFs. This still produces a complete full search index and full manifest at the end.

Temporary fixture mode is available for parser and pipeline development:

```bash
OPENMEVZUAT_FIXTURE_MODE=true clojure -M:openmevzuat build
```

Fixture mode is not a substitute for official-source canonical data.

## License and Data

Project code and project-specific structured data are licensed under the repository license unless otherwise noted. Official legal texts remain attributable to their official public sources.
