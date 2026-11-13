# Public API compatibility gate

Version 1 of the scheduler's contract ([docs/api/openapi-v1.yaml](../../docs/api/openapi-v1.yaml)) changes only additively: a new operation, optional request field, response field, error code or enum value, each raising the minor version. A breaking change is published as `/api/v2` beside v1, never in place. The [integration guide](../../docs/integration-guide.md) states the rule to hosts; this gate enforces it.

## What it checks

The CI job "Public API compatibility" runs [openapi_compat.py](../openapi_compat.py) on every pull request and every push to `main`. It compares the spec with the one it replaces (the pull request's base, or the commit before the push) using [oasdiff](https://github.com/oasdiff/oasdiff) 1.35.0, and fails when:

- **A change can break a v1 client:** oasdiff reports it at warning level or above, and [accepted-breaking-changes.txt](accepted-breaking-changes.txt) does not list it. Warnings count, because a changed `pattern` is how a narrowed value shows up.
- **The API changed without a new version:** oasdiff's changelog lists a change, but `info.version` did not rise. A description-only edit lists no change and needs no new version.
- **The version left v1 or went down:** `info.version` must stay at major version 1 and never decrease.

A new enum value in a response, such as a new error code, is additive by the v1 rule, and readers that reject unknown values take each new minor version first. oasdiff calls it an error by default, so [severity-levels.txt](severity-levels.txt) lowers `response-property-enum-value-added` to info. That file takes no comments; this paragraph is its explanation.

## The one exception

v1 allows one kind of narrowing: limiting a value to bounds no host sends, confirmed with WaterFlex Software before it is deployed. Spec 1.7.0 (cost rates) and 1.11.0 (the lowercase `requestId` pattern) are the two so far. For such a change:

1. Confirm with WaterFlex Software that no host sends a value the change refuses.
2. Copy the lines the failing gate prints under "To accept" into [accepted-breaking-changes.txt](accepted-breaking-changes.txt), with a comment above them naming the spec version and the confirmation.
3. Describe the narrowing in the spec's version history and the change table in [docs/stateless-api-design.md](../../docs/stateless-api-design.md).

Each line matches one change exactly (method, path and oasdiff's text), so an entry cannot excuse a different change. Entries stay in the file as the record of what was accepted.

## Run it locally

Download oasdiff 1.35.0 from its [releases page](https://github.com/oasdiff/oasdiff/releases/tag/v1.35.0) and check it against the release's `checksums.txt`. Then compare your working tree's spec with `main`:

```powershell
git fetch origin main
python infra/openapi_compat.py --against origin/main --oasdiff <path-to-oasdiff.exe>
```

```text
Compatible within v1: 1.11.0 to 1.12.0.
```

The unit tests need no oasdiff: `python -m unittest test_openapi_compat` from `infra/`.
