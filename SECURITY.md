# Security Policy

## Reporting a vulnerability

Report security vulnerabilities privately through GitHub's **Report a vulnerability** button under the repository's Security tab. Do not open a public issue for a vulnerability.

Please include what you did, what happened, and what you expected. A proof of concept helps.

This is an experimental project maintained in spare time, so response times are best effort.

## Scope

In scope:

- the update and rendering pipeline in `src/`
- the GitHub Actions workflows in `.github/workflows/`
- anything that could let a third party inject content into canonical data

Out of scope:

- vulnerabilities in mevzuat.gov.tr, Resmî Gazete, or other official sources — report those to the operators of those services
- the accuracy of official legal text itself

## Incorrect legal text is not a security issue

If canonical text does not match the official source, open a normal issue using the **Canonical text issue** template instead. That is a data correctness problem, not a vulnerability, and handling it in public is fine and preferred.

## Türkçe

Güvenlik açıklarını lütfen herkese açık issue olarak değil, deponun Security sekmesindeki **Report a vulnerability** düğmesiyle özel olarak bildirin.

Canonical metnin resmi kaynakla uyuşmaması bir güvenlik açığı değildir; bunun için **Canonical text issue** şablonuyla normal bir issue açın.
