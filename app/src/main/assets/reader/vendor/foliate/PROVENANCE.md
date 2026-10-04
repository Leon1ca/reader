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
