# biliSendCheck

A userscript that checks whether a posted Bilibili comment is visible to other people, with local history, rechecking, and JSON backup exchange with BiliIntl Unrestrict.

## Installation

1. Install Tampermonkey in your desktop browser.
2. Open [`biliSendCheck.user.js`](biliSendCheck.user.js) and install it through your userscript manager. On GitHub, open the file's **Raw** view to start installation.
3. Reload the Bilibili page and sign in to the account that posted the comments.
4. Post a comment. The script captures supported comment submissions through the page's `fetch` API, waits briefly, and checks its visibility.
5. Use the floating button to open history, search, settings, and diagnostic logs.

The browser script does not require root or LSPosed. BiliIntl Unrestrict is the separate Android module for the international Bilibili app.

## What it does

- Compares anonymous and authenticated views of a comment.
- Uses `seek_rpid` for root comments and bounded pagination for replies.
- Rechecks anonymous visibility before confirming a self-only result.
- Keeps comments locally with search, status filters, rechecking, JSON import/export, and optional deletion through Bilibili's API.
- Preserves an existing determinate result and its check time when a later check is inconclusive.

| Status | Meaning |
| --- | --- |
| Normal | Found in the anonymous view. |
| Self-only | Found in the authenticated view but not in the anonymous checks. |
| Invisible | Returned with an `invisible` flag. |
| Deleted / unavailable | The probes indicate absence or an unavailable comment section; this does not prove physical deletion in every access-restricted case. |
| Root gone | The parent comment could not be found; the reply itself could not be verified. |
| Unknown | The available responses or pagination do not support a conclusion. |

`UNDER_REVIEW` remains a supported backup status; the comment probe does not currently produce it. The script also has a separate dynamic-page check that can report suspected review.

## Manual backup exchange

Export JSON from either the Android module or this script, then import it on the other side. There is no background synchronization, shared login session, or propagation of local deletions.

The existing top-level array and all 13 record fields are retained:

```text
rpid, oid, type, root, parent, uid, source_id, origin_url,
message, initial_status, status, post_time, timestamp
```

Exports contain exact numeric IDs. Android stores them as `Long`; the script preserves large IDs as strings internally and writes validated ID fields as numeric JSON tokens. Comment content uses normal JSON escaping. Imports also accept decimal-string IDs, but reject invalid, duplicated, out-of-range, or untrusted numeric IDs before saving anything.

For an existing comment:

| Local result | Imported result | Outcome |
| --- | --- | --- |
| Unknown | Determinate | Use the imported result. |
| Determinate | Unknown | Keep the local result and its check time. |
| Determinate | Different determinate result | Use the imported result and its original check time, even if older. |

Existing nonempty text, original posting time, and a determinate initial status are preserved. Missing metadata can be filled from the import. Conflicting comment-section or nonzero identity fields reject the entire import rather than redirecting future checks to another object.

After import, the summary offers **View changes** to temporarily filter existing comments whose status changed and show before/after labels. It requires no confirmation queue and disappears when dismissed or replaced by another import.

## One-step local undo

Before a local removal, clear, or import that changes records, the script saves one full snapshot in a separate userscript storage key. The next such operation replaces it. Exporting, rechecking, and no-op imports do not replace the snapshot.

**Undo** restores the complete list from that snapshot, including replacing changes made since the operation, and then consumes the snapshot. It cannot restore a comment physically deleted on Bilibili. The Android module provides the same behavior using a file in its private app directory.

## BiliPai and older clients

The record layout matches the BiliPai Comment Fraud History Center contribution in [PR #734](https://github.com/jay3-yy/BiliPai/pull/734). This is format compatibility based on that contribution, not a guarantee about every upstream release or matching detection results.

The contributed BiliPai implementation imports by replacing records and maps unrecognized statuses such as `ROOT_GONE` to Unknown in its UI. Older clients do not gain this project's merge safeguards or undo behavior just by reading the JSON file. Keep an exported copy before using their import or recheck features.

## Limits and storage

- Capture currently covers page `fetch` submissions; other submission paths may not be captured.
- Browser credentials, network failures, risk controls, and changing pagination can produce a different result from Android. An inconclusive check does not erase an earlier determinate result, but a later determinate check can revise it.
- History and logs are local to the userscript manager. Detection and explicit server deletion contact Bilibili; no third-party sync service is used.
- Do not pass backups through tools that round large JSON numbers. Previously rounded IDs cannot be reconstructed from their rounded values.

## Tests

No npm installation is required:

```bash
node userscript/tests/detector.test.cjs
node userscript/tests/backup.test.cjs
```

Run these commands from the repository root. Fixtures use synthetic identifiers and comment text.

## License

[MIT](../LICENSE). See the [repository acknowledgements](../README.md#致谢) for the original dual-view detection idea.
