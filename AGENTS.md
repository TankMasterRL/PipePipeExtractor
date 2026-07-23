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

## Extractor architecture (essentials)

- `NewPipe.init(Downloader, Localization, ContentCountry)` wires in a `Downloader`
  and localization. `NewPipe.getService(int|String)` / `getServices()` look up services.
- Services (`ServiceList`): YouTube `0`, SoundCloud `1`, MediaCCC `2`, PeerTube `3`,
  Bandcamp `4`, BiliBili `5`, NicoNico `6`. Each extends `StreamingService` and lives
  under `org.schabi.newpipe.extractor.services.<name>`.
- High-level access goes through `Info` factories with static `getInfo(...)` and, for
  list types, `getMoreItems(...)`: `SearchInfo`, `StreamInfo`, `ChannelInfo`,
  `ChannelTabInfo`, `PlaylistInfo`, `CommentsInfo`, `KioskInfo`, `FeedInfo`.
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

## Conventions

- Develop on feature branches; the default branch is `main`.
- Keep changes focused and match existing patterns; reuse existing utilities before
  adding new ones.
