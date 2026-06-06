# Contributing

OpenMevzuat is experimental and intentionally small in v0.1.

## Principles

- Do not use AI to generate or rewrite canonical law text.
- Keep canonical files human-readable and Git-diff-friendly.
- Keep side effects separate from parsing, rendering, hashing, and normalization.
- Prefer deterministic output over convenience.
- Verify source-linked text against official public sources.

## Contributor license requirement

By submitting a pull request, you confirm that you have read and agree to `CONTRIBUTOR_LICENSE.md`.

This means:

1. Your contribution is licensed under AGPL-3.0-only.
2. You grant the project maintainer additional rights to use, sublicense, and relicense your contribution, including under a separate commercial license.
3. You confirm that you have the legal right to submit the contribution.
4. You retain copyright ownership of your contribution.

If you do not agree to `CONTRIBUTOR_LICENSE.md`, do not submit a pull request.

Pull requests that do not agree to the contributor license policy must not be merged.

## Pull request rule

No contributor license agreement, no merge.

Every pull request must keep the checkbox in `.github/pull_request_template.md` confirming agreement with `CONTRIBUTOR_LICENSE.md`.

## Future CLA automation

The project may later use a CLA automation tool to verify contributor agreement.

Until then, agreement is recorded through the pull request checklist and the repository contributor policy.

## Development

Run the main pipeline:

```bash
clojure -M:openmevzuat update
```

Run tests:

```bash
clojure -M:test
```

Temporary fixture mode may be used for parser and pipeline development:

```bash
OPENMEVZUAT_FIXTURE_MODE=true clojure -M:openmevzuat build
```

Do not commit fixture-derived canonical data as official legal text.
