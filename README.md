# OpenMevzuat

Versioned Turkish legislation from official public sources.

OpenMevzuat stores legislation as human-readable, article-level Markdown files with EDN metadata.

## Status

Experimental. Not an official source.

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

