# Foliate paginator

Source: https://github.com/readest/foliate-js
Commit: `341dc63cda7ad26b31ada4ee905e40b5020da88a`
License: MIT, retained in `LICENSE`.

Only `paginator.js` is vendored. A local patch replaces the content iframe's
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
