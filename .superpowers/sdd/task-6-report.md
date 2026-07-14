# Task 6 Report: Frontend V2 Client Types

## Status

DONE

## Scope

Implemented only the frontend V2 response contract and API client requested by Task 6.

Modified files:

- `apps/web-react/src/types.ts`
- `apps/web-react/src/api/client.ts`

Added:

- `V2SignalResponse`
- `V2SampleSignalParams`
- `fetchV2SampleSignal(params)`

The client builds the optional `companyName` and `strategyCode` query parameters only when provided, and calls the backend route through the existing Axios `/api` base URL as `/api/v2/signals/sample`.

## Verification

Command:

```bash
cd apps/web-react
npm run build
```

Result: PASS. TypeScript compilation (`tsc -b`) and the Vite production build completed successfully.

Separate typecheck command: not available. `apps/web-react/package.json` defines `dev`, `build`, `preview`, and `test`, but no `typecheck` script.

Additional check:

```bash
git diff --check
```

Result: PASS.

## Commit

The task files were committed with:

```text
feat: add frontend v2 signal client
```

## Concerns

The worktree contained a pre-existing modification to `docs/superpowers/plans/2026-07-10-soft-valuation-context-p01.md`. It was not modified, staged, or reverted.

## Review Fix: V2 Signal Audit Fields

The controller response omitted audit fields that were already present on `StrategySignal` and persisted in the ledger payload. The response contract now exposes `sourceQuality`, `signalProvenance`, and `replayPayload`, and the frontend `V2SignalResponse` matches those JSON fields.

`V2SignalControllerTest` now asserts the serialized audit values and replay payload source markers. The red test first failed because `$.sourceQuality` was absent from the JSON response; after exposing the fields, the targeted Maven test and frontend production build passed. `git diff --check` also passed.
