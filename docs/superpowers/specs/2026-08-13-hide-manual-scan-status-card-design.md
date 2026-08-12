# Hide Manual Scan Status Card Design

## Goal

Keep the short-term manual scan workflow unchanged while removing the large
manual scan status card that appears above the threshold controls after the
user starts or completes a scan.

## Selected Scope

This is a presentation-only change in the React short-term page:

- do not render `ScheduledSnapshotStatus` when the current report origin is
  `MANUAL`, regardless of whether the manual job is running, completed,
  blocked, or failed;
- continue to render scheduled scan progress and scheduled snapshot status for
  the `SCHEDULED` origin;
- preserve the existing manual scan request, background polling, button loading
  state, inline loading panel when no report exists, error panel, completion
  toast, candidate results, and restored results after page navigation.

The shared `ScheduledSnapshotStatus` component remains available for scheduled
snapshots. Its manual-copy support may remain for compatibility, but the
short-term page no longer routes manual snapshots into it.

## Alternatives Considered

- Hide only the `RUNNING` manual card: rejected because completed, blocked, or
  failed manual cards would still consume the same unnecessary space.
- Remove all scan status cards: rejected because scheduled task status remains
  useful and is outside the requested scope.

## Data Flow and Error Handling

`runManualScan` and the short-term scan store keep ownership of job creation,
polling, result restoration, errors, and toast notifications. The page changes
only the condition that selects the top status presentation. No API, store,
backend, database, strategy, or V4 decision-gate behavior changes.

## Verification

- A manual snapshot does not render the manual status card or its status copy.
- Starting a manual scan still calls the existing API flow and shows the button
  loading state or the existing compact loader when appropriate.
- Manual scan errors and results remain visible through their existing UI.
- Scheduled running and completed snapshots continue to render their status UI.
- The focused short-term page tests, complete React tests, and React production
  build pass.
