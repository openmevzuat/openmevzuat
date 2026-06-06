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
clojure -M:openmevzuat update
clojure -M:openmevzuat build
clojure -M:openmevzuat clean-derived
```

`update` fetches configured official sources, normalizes text, parses articles, renders canonical files, writes EDN metadata, and generates derived outputs.

Temporary fixture mode is available for parser and pipeline development:

```bash
OPENMEVZUAT_FIXTURE_MODE=true clojure -M:openmevzuat build
```

Fixture mode is not a substitute for official-source canonical data.

## License and Data

Project code and project-specific structured data are licensed under the repository license unless otherwise noted. Official legal texts remain attributable to their official public sources.
