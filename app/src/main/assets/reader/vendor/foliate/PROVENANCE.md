# Foliate paginator

Source: https://github.com/readest/foliate-js
Commit: `341dc63cda7ad26b31ada4ee905e40b5020da88a`
License: MIT, retained in `LICENSE`.

`paginator.js` and the independently registered `fixed-layout.js` module are vendored. A local patch replaces the content iframe's
`sandbox="allow-same-origin allow-scripts"` with `allow-same-origin`. Android
Chromium does not need the upstream WebKit event workaround. Every content
response also has HTTP CSP `script-src 'none'`. The shell stays outside that
sandbox and is the only code allowed to send native events.

A second patch adds a global maximum of eight active views, evicts off-screen
sections more than two section indices behind/ahead, and compensates removal of
preceding view height to preserve the visible position. Upstream's original
trim only evicted future views and could retain all historical sections.

A third lifecycle patch disconnects a released view's ResizeObserver and
removes its iframe, stopping pending navigation. The host replaces its paginator
on a superseding open and ignores every old instance's events.

A fourth lifecycle patch disconnects the paginator's ResizeObserver during
destroy. Upstream observes its private container but attempts to unobserve the
host element, leaving the detached container observed. The regression test
counts calls to a released paginator's render method after a cross-flow rebuild
and final close; both must remain zero.

Patch removal conditions: upstream supplies an inert Android sandbox, a global
view budget with anchor-preserving backward eviction, and release that cancels
all view observer/navigation activity, and paginator destroy disconnects its
container observer respectively. Reproduce patches with
`scripts/tests/verify-vendor.py`; `paginator.patch` is the complete unified diff.

Updates must rerun bounded loading, pagination, inert content, Range restoration,
font reflow and lifecycle tests. No Readest application source is included.

`fixed-layout.js` is independently pinned to the same MIT commit. Its original and packaged hashes and complete replayable patch are registered in manifest.modules. The fixed-layout patch removes script permission and its unused polyfill import (the trusted host checks native features), reserves every loading/hidden/detached frame before allocation with a hard four-frame budget, evicts before acquisition, cancels all source/frame waits on release, guards late callbacks, and normalizes viewport and RTL offset-parent coordinates. The public zoom contract is shared between paginated and continuous modes: numeric source scale, fit-width, and fit-page are applied to both placeholders and loaded frames. Consecutive center pages each retain a separate spread (upstream overwrote the previous center page), so AUTO can honor author spread-none without dropping pages. Paginated speculative preload is disabled; required spread pages and continuous visible pages still load. Remove these patches when upstream supplies equivalent safety, cancellation and budget guarantees; rerun actual 30-page/RTL/two-axis restoration/loading-close/gesture tests. No Readest application code is included.

A decorative blank frame at the left/right spread boundary is explicitly marked by the engine and omitted from `getContents()`; its reservation remains in the four-frame lease count. Real loading frames are retained even before a section index or canonical marker exists. This prevents a blank `about:blank` document from blocking canonical readiness. Remove this patch when upstream distinguishes source documents from decorative spread partners; rerun one-page AUTO left/right, delayed real-source readiness, and frame-budget tests.

An authored vertical-writing geometry patch respects the same maximum column count as horizontal text. It removes the extra vertical column and reduces infeasible column counts only after re-measuring the actual grid. A width truncated to zero is rejected explicitly instead of silently reverting CSS to `auto`. Source writing-mode, canonical text and locators are unchanged. Remove this patch when upstream handles the declared upper bound and positive integer column geometry; replay original vertical-rl/lr negative-width fixtures and paginated/scrolled source-position tests.

The principal-flow patch derives the outer axis from body. Only a document whose
meaningful direct content elements all have the same vertical mode, with no
meaningful direct body text, aligns the viewer column root to that mode as an
explicit compatibility adaptation. Mixed horizontal/vertical content keeps its
body principal mode. Default page progression follows the source document; a
published ltr/rtl declaration remains binding. Preload direction caches use the
full writing-mode and scrolled rectangle mirroring uses the primary frame's
local size, never the aggregate width of preloaded frames.

For scrolled mixed documents, direct orthogonal blocks retain their authored
height and writing-mode but receive a bounded-width local horizontal scrollport.
Range navigation minimally reveals the source glyph within that port. Page
commands consume the active source block before advancing the main flow, and
start/end guards consider that active block's remaining extent. Explicit jumps
to subsequent horizontal content do not revisit the earlier block. The paginator
imports the application's original `../../viewport.js` geometry helper solely
for the active local port's first visible Range; it respects iframe and ancestor
overflow clips and does not add or rewrite canonical text. Remove this local
adaptation when upstream safely fragments/navigates orthogonal overflow and
reports clipped source ranges. Replay `renderer-vertical.cjs` (source first,
next, exact previous, middle, last and flow changes) and
`renderer-vertical-mixed.cjs` (opposite spine axes, preserved mixed styles,
1000-unit local overflow, independent glyph membership, next/previous and short
horizontal-tail endpoint guards). These are local browser tests, not a claim of
completed native or publication-wide compatibility acceptance.

The non-animated page-turn patch replaces the unconditional 100ms lock with the
existing real rendering-turn helper after awaited scroll/section navigation.
Animated paths still await their original visual animation completion. The
trusted protocol hosts verify loaded source documents, fonts, images, stable
frame geometry and actual visible source glyphs before publishing page Ready;
new/unvalidated sections retain the complete resource validation path. This
removes an artificial latency floor without changing animation preferences or
acceptance thresholds. Remove when upstream releases non-animated page locks on
actual operation/render completion. Recheck source pagination, cross-section
resources, vertical/mixed writing modes, cancellation and page timing.

The cached-source navigation patch waits for the actual View load completion before
resolving an anchor. A preloaded about:blank iframe is not a loaded publication.
View release aborts its load listener and settles the wait; command supersession
and replacement-view guards prevent late source completion from navigating the
retired request. Real missing blocks and the host's existing bounded loading
failure remain errors. Remove this patch when upstream exposes equivalent
cancellable cached-load readiness. Replay `renderer-txt-preload.cjs`: trusted wheel
preload with a delayed real HTTP response, rapid supersession, close, missing
source block, and the original host deadline. Source text and requested anchors
are not substituted.


The navigation-owner patch binds the requested index, actual View, document and
sequence through every asynchronous source/adjacent load and anchor scroll.
A delayed passive container-scroll callback cannot choose the old visible
section while a target navigation owns the primary. Supersession, bounded host
failure and destroy revoke that owner; only the current sequence releases it.
Real missing source blocks remain errors. Remove when upstream provides the
same document/geometry ownership across navigation awaits. Replay
`renderer-txt-navigation.cjs`: an actual preceding page scroll plus a finite
HTTP-delayed target, supersession, close, genuinely missing source and the
unchanged host deadline. The public no-preload control isolates ownership from
view eviction; ordinary preloading remains covered separately.

An optional trusted-host observer records existing target load, style/frame,
render, anchor, scroll and asynchronous font-expansion boundaries. It is
command/sequence-bound, failure-isolated and performs no extra resource read,
prewarm, frame wait or navigation. The host aggregates fixed phase enums under
its existing bounded summary; it is diagnostic evidence, not an acceptance or
performance endpoint. Remove when equivalent upstream lifecycle observation is
available, retaining the original loading/cancellation/Ready contracts.


Both fixed-layout frame creation paths now await the trusted host's optional
beforeDocumentPublish hook while retaining their real frame lease and abort
controller. The host de-duplicates SHA checks by document identity and commits
validation only for the current renderer, frame document and live signal. A
loaded/public/interactive document has completed admission; pending and verifying
frames remain in the four-frame allocation budget and full frame diagnostics.
Canonical failures reach the host error channel, including late neighbours, and
close/rebuild cannot publish a late digest. Decorative blank partners keep their
lease and explicit placeholder status. Remove when upstream offers an equivalent
awaited source-admission lifecycle; do not remove canonical validation.

Replay renderer-fixed.cjs (the original thirty-page, every-admitted-document,
visible-anchor and four-frame checks remain) plus renderer-fixed-admission.cjs.
The latter delays genuine SHA completion after its real digest and a finite HTTP
resource response in both flows; success, invalid SHA, close and renderer rebuild
verify actual pending/admitted membership and late-result cancellation. This is
a controlled browser regression, not native/optimized performance evidence.

Cancellation resets stabilization even without a current navigation owner. Adjacent loads capture the navigation sequence and recheck both ownership and existing views after asynchronous source reads, before allocating a frame.

Mixed writing-mode navigation clears the destroyed cached owner lease before creating its replacement. A preload whose completed frame has been claimed by a navigation yields direction handling to that owner. Fixed-layout cancellation aborts pending frame/source work while retaining the engine and loaded pages; unawaited spread regroup/mode-switch tasks handle cancellation and surface genuine failures through documenterror.
