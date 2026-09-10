# Screenshots for PR #41 — the rail's disclosure groups

An orphan branch, carrying nothing but these five images. It exists so a pull request can
show what it changed without `main` picking up half a megabyte of PNG that no build reads and
no reviewer ever diffs. Nothing merges from here, in either direction.

**Do not delete this branch.** `raw.githubusercontent.com` resolves by ref, not by object, so
dropping the branch turns every image in #41 into a 404 — including after the PR has merged,
which is exactly when somebody reading back through the history wants to see them. The images
are the reason the PR is legible a year from now, and 450KB parked on a ref `main` never
touches is a cheaper way to keep them than carrying them in the tree.

The same goes for renaming it: the URLs in the PR body are literal, and nothing rewrites them.

Captured off `design/rail-collapsible-groups` at 2x through headless Chrome — the same build
the PR proposes, not the branch stack on top of it, which is why the topbar here still carries
its wordmark and its two full button labels.

| file | state |
|---|---|
| `rail-default.png` | Học closed — what a first visit gets, signed in |
| `rail-hoc-open.png` | Học open — all eleven views |
| `rail-signal-dot.png` | Chơi closed while a live round is running, standing on the leaderboard |
| `sheet-default.png` | the phone sheet, Học closed: 418px, no scroll |
| `sheet-hoc-open.png` | the phone sheet, Học open: 520px cap against 579px of content |
