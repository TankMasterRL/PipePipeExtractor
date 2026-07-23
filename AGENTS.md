# Agent guidance

Guidance for AI coding agents (Claude Code, Codex, Cursor, …) working in this
repository. `CLAUDE.md` imports this file, so keep shared guidance here.

## What this is

PipePipe Extractor is the extraction backend for
[PipePipe](https://codeberg.org/NullPointerException/PipePipe). It is a fork of
NewPipe Extractor and keeps the upstream package namespace
`org.schabi.newpipe.extractor`, even though it is published under the Maven group
`com.github.InfinityLoop1308.PipePipeExtractor` (via JitPack). Licensed under GPL-3.0.

## Build & modules

- **Gradle**, Groovy DSL (`build.gradle`, `settings.gradle`) — no Kotlin DSL, no
  version catalog. Dependency versions are declared inline in each module.
- **Java 25 toolchain** for all projects (see the root `build.gradle`). The Gradle
  wrapper pins Gradle 9.5.1.
- Modules (`settings.gradle`):
  - `:extractor` — the main library. Uses the Square **Wire** plugin to generate
    Java from the `.proto` files in `extractor/src/main/proto`.
  - `:timeago-parser` — helper for parsing relative "time ago" dates.
  - `:mcp-server` — an MCP server exposing the extractor's public API (Java
    `application`; depends only on `:extractor`). See the README.
- The root build **disables the `test` and `check` tasks for all projects**, then
  `:extractor` and `:mcp-server` re-enable their own `test` task. So there is no
  aggregate `check`; run module tasks directly.

## Tests

- `./gradlew :extractor:test` — the extractor's JUnit 5 unit tests.
- `./gradlew :mcp-server:test` — the MCP server's unit tests.
- Tests are **offline unit tests**. Unlike upstream NewPipe, there is **no**
  `DownloaderFactory` / mock-response harness and **no** `-Ddownloader`
  MOCK/REAL/REC mechanism; do not assume one exists. Prefer tests that need no network.
- There is **no checkstyle** and no in-repo CI. Match the surrounding code style;
  source files carry no per-file license header.

## Working practices

- **Investigate before asserting.** Read the actual extractor / base class before
  describing behavior. Don't answer questions about a service's extraction flow,
  `LinkHandler` URL rules, or the YouTube signature/SABR/PoToken logic from memory —
  this fork has diverged from upstream NewPipe in ways that make guessing unreliable.
- **Ground every claim in evidence.** Only report a step as done or passing if a tool
  result backs it (a build that ran, a test that passed, a diff that was reviewed). If
  something can't be checked from the current session (no JDK 25 locally, live-network
  behavior, on-device PipePipe app integration), say so plainly.
- **Keep changes minimal and scoped.** Don't add a build system, abstraction, module, or
  dependency this repo doesn't already have. When adding or changing a service, mirror
  the existing package layout (`services/<name>/{extractors,linkHandler,search/filter}`)
  rather than inventing a parallel structure — YouTube is the most complete example.
- **Respect the stability contracts.** `ServiceList` integer ids are load-bearing and
  must never change or be renumbered (`ServiceList.java` documents appending new
  services with "the next free id"); the public API and the serializable `Info`/
  `InfoItem` model are a compatibility surface consumed by the PipePipe app — don't
  reshape them casually.
- **Isolate per-item failures; don't over-defend elsewhere.** List extraction already
  isolates a single bad item via the collector pattern — `InfoItemsCollector.commit()`
  catches `ParsingException` and accumulates it via `addError` instead of aborting the
  whole list. Use that pattern rather than wrapping call sites in try/catch. Validate
  only at real boundaries (network responses, nullable JSON fields, URL acceptance,
  page/continuation tokens).
- **Delete, don't comment out.** When a design changes, remove the superseded code
  outright — git history is the record of what was tried and why.
- **Treat destructive git operations with care.** Before any command that could discard
  uncommitted work (`checkout`/`restore`/`reset`/`clean`, `rm -rf`), run `git status`
  first and stash or commit anything in progress. Don't bypass hooks or reach for
  `--force` to get past an obstacle — diagnose the underlying failure instead.

## Extractor architecture

- `NewPipe.init(Downloader, Localization, ContentCountry)` wires in a `Downloader`
  and localization. `NewPipe.getService(int|String)` / `getServices()` look up services.
- Services (`ServiceList`): YouTube `0`, SoundCloud `1`, MediaCCC `2`, PeerTube `3`,
  Bandcamp `4`, BiliBili `5`, NicoNico `6`. Each extends `StreamingService` and lives
  under `org.schabi.newpipe.extractor.services.<name>`.
- Two collaborating hierarchies, as in upstream NewPipe:
  - **`LinkHandlerFactory` → `LinkHandler`** — URL handling. A factory validates a URL,
    extracts the canonical id, and rebuilds a clean URL; `ListLinkHandlerFactory` adds
    content/sort filters, `SearchQueryHandlerFactory` handles search queries. The
    resulting immutable `LinkHandler`/`ListLinkHandler` is passed into an extractor.
  - **`Extractor` → `Info`** — data extraction. An extractor is constructed with a
    service + a `LinkHandler`, `fetchPage()` loads the page, then getters parse fields
    lazily. High-level `Info` factories with static `getInfo(...)` and, for list types,
    `getMoreItems(...)` drive an extractor and assemble a plain, serializable result:
    `SearchInfo`, `StreamInfo`, `ChannelInfo`, `ChannelTabInfo`, `PlaylistInfo`,
    `CommentsInfo`, `KioskInfo`, `FeedInfo` — this is the primary API most consumers call.
- List pagination uses `Page` + `ListExtractor.InfoItemsPage`: an info/page exposes
  `getNextPage()`; feed it back into the matching `getMoreItems(...)` until the page is
  no longer `Page.isValid(...)`.
- Search filters use the `search.filter` model: content/sort filters are
  `List<FilterItem>` (each `FilterItem` has an int identifier and a name); a service's
  `SearchQueryHandlerFactory` exposes `getAvailableContentFilter()` /
  `getAvailableSortFilter()` (returning a `Filter` of `FilterGroup[]`) and
  `getFilterItem(int)`.
- The `Downloader` contract is okhttp-flavored: implementations must provide both
  `execute(Request)` and `executeAsync(Request, AsyncCallback)` (the async form returns
  a `CancellableCall` wrapping an `okhttp3.Call`). YouTube stream extraction drives
  several async calls concurrently and awaits/cancels them.
- PipePipe-specific extensions to be aware of: BiliBili & NicoNico services, bullet
  comments (danmaku), SponsorBlock, YouTube SABR/PoToken handling, and a trust-all TLS
  setup installed by `NewPipe.init`.

## Branching & commit conventions

- Single default branch: **`main`** (unlike upstream NewPipe/its forks, there is no
  separate `dev`/`master` split).
- **Commit subjects follow this repo's own conventional-commit style** — a lowercase
  type prefix (`fix:`, `feat:`, `perf:`, `dev:`) plus an imperative summary, e.g.
  `fix: preserve SABR demand backoff deadlines`, `feat: attach session-bound PoTokens to
  YouTube player requests`. This differs from upstream NewPipe's `[Service] Subject`
  bracket convention — match what's actually in `git log`, not the upstream style.
- **Credit AI assistance with an `Assisted-by: <tool>` trailer** (e.g.
  `Assisted-by: Claude Code`) on commits an AI tool helped author, mirroring upstream
  NewPipe's convention for AI-assisted contributions.
- **Don't put agent-session links** (e.g. `https://claude.ai/code/...`) in commit
  messages or PR descriptions.
- Keep changes focused and match existing patterns; reuse existing utilities before
  adding new ones.
- Don't open a PR unless asked to; once one is open, subscribe to its activity
  (comments, CI, reviews) and drive it toward a mergeable state rather than waiting to
  be asked.
- **These repo conventions take precedence over a specific agent harness's own
  default behavior** (e.g. a harness-inserted commit trailer, or a default of asking
  before subscribing to a PR) — follow what's documented here for this repository
  rather than a tool's built-in defaults.
