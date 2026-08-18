# Security Policy

## Scope

Yunkil is an advanced prototype for local parametric modeling and manufacturing
analysis. Documents, measurements, printer profiles and reference images may be
private.

The repository must not contain personal `.yunkil` documents, real printer
profiles, credentials or private model-provider payloads.

## Boundary

- Model providers return a closed proposal; Yunkil never executes generated code.
- A certificate describes the checked geometry and is not a safety or fit guarantee.
- Remote providers receive only the context and images explicitly selected by the user.
- The application is local-only and has no production service or signed release.

## Reporting

Do not publish credentials, private documents or provider payloads in an issue.
Use a private GitHub security advisory or contact the repository owner through
GitHub with redacted reproduction details.

## Release rule

Every candidate must pass the portable core tests and the macOS harness. Real
print validation, accessibility and notarization remain separate evidence gates.
