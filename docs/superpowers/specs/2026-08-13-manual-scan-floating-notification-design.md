# Manual Scan Floating Notification Design

## Goal

Give the user continuous, lightweight feedback for a manual short-term scan
without restoring the large scan-status card. The user must always be able to
tell that a scan started and, when no candidate result appears, why it did not
produce one.

## Selected Approach

Extend the existing global bottom-right Toast system with keyed replacement and
optional persistence. A manual scan owns one notification with the stable key
`short-term-manual-scan`. Each phase updates that same notification instead of
stacking a new message.

This approach reuses the current visual language and global viewport. A
scan-specific floating panel would duplicate the Toast system, while a modal
dialog would interrupt normal page use.

## Interaction Flow

### Scan starts

Clicking `重新扫描` or `应用阈值` immediately creates or replaces the keyed
notification:

- tone: information;
- message: `短线扫描已开始，正在获取实时行情…`;
- duration: persistent until the job reaches a terminal state or the user
  closes it;
- the existing button loading state and compact no-report loader remain.

Starting another manual scan replaces any earlier scan notification. It must
not create a stack of stale scan messages.

### Scan completes with candidates

For `FINAL_READY` with one or more candidates, the same notification becomes a
success message containing the candidate count. It closes automatically after
5 seconds and still has a manual close button. A defensive `FINAL_READY` result
with zero candidates is treated as a persistent no-candidate warning, not as a
success.

### Scan completes without an actionable result

The same notification becomes a persistent warning for:

- `NO_TRADE`: say that the scan completed with no eligible candidate and show
  the server message or the most relevant reason;
- `DATA_BLOCKED`: say that data quality blocked the result and include the
  blocked reasons;
- `CACHE_PREVIEW`: make clear that the result is only a cached preview and is
  not a current buy point;
- `FINAL_PENDING` or `PRESELECT_READY`: make clear that an actionable manual
  result is not ready and include the server explanation.

Warnings never auto-dismiss. The user must close them with the existing close
button.

### Scan fails

The same notification becomes a persistent error when:

- job creation fails;
- job polling fails;
- the job reports `FAILED`;
- a successful job does not contain a report.

The message includes the specific server or network error. Errors never
auto-dismiss and require manual close.

If the user closes a running notification, the terminal notification must
appear again when the job completes, because the final outcome is a separate
important event.

## Notification API

The global Toast API remains backward compatible: existing calls such as
`toast.success(message)` retain their current 3.2-second behavior.

Add optional notification options:

- `key`: replaces an existing Toast with the same key;
- `durationMs`: controls auto-dismissal;
- `persistent`: disables the timer.

`persistent: true` takes precedence if a caller also supplies `durationMs`.
Manual scan callers use one dismissal option at a time.

Pushing a Toast with an existing key updates its type, message, and dismissal
policy while retaining one visible row. A keyed update after manual dismissal
creates a new row, allowing a closed progress notification to reappear with the
terminal result.

Only the manual short-term scan uses the new persistent keyed behavior in this
change. Other application notifications and scheduled-scan notifications keep
their existing semantics.

## Message Construction

The scan store remains the source of truth for the outcome. Notification text
uses, in order:

1. the terminal status meaning;
2. the nonblank server message;
3. nonblank `blockedReasons`, joined with `；`;
4. a deterministic local fallback when the server supplies no explanation.

Repeated text is included only once. Messages may wrap in the existing floating
card; no status details are put back into the main page layout.

## State and Data Boundaries

- Do not modify scan APIs, polling intervals, backend result status, database
  records, candidate ranking, V4 gates, or scheduled scan control.
- Keep the large manual status card removed.
- Keep background polling and result restoration when navigating away and back.
- Keep inline error/result rendering as a durable page fallback; the floating
  notification is additional feedback, not the sole source of truth.
- Do not invoke an external model as part of implementation or acceptance.

## Accessibility

- Information and success notifications use polite live announcements.
- Warning and error notifications use alert semantics.
- Every persistent notification has a keyboard-accessible close button with an
  accessible label.
- Color is not the only status signal; icon and text identify the outcome.

## Verification

Automated tests must prove:

- an existing unkeyed Toast still auto-dismisses after 3.2 seconds;
- a persistent Toast remains after timers advance and can be manually closed;
- a keyed update replaces rather than stacks a notification;
- starting a manual scan creates the persistent information notification;
- candidate success replaces it and auto-dismisses after 5 seconds;
- `NO_TRADE`, `DATA_BLOCKED`, no-report, job failure, and request/poll failure
  each replace it with a persistent notification containing a reason;
- the manual status card remains absent and scheduled status UI remains intact.

The focused tests, complete React test suite, React production build, and live
local scan interaction must pass before deployment. The Web container is then
rebuilt without replacing the API container or database volume.
