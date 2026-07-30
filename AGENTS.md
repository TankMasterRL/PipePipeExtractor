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

- The root build **disables the `test` and `check` tasks for all projects**, then
  `:extractor` and `:mcp-server` re-enable their own `test` task. So there is no
  aggregate `check`; run module tasks directly.
- `n8n-node/` is **not** a Gradle module and is absent from `settings.gradle`: it is a
  standalone npm/TypeScript package (the `n8n-nodes-pipepipe` community node) that
  talks to `:mcp-server` over MCP Streamable HTTP. Build and test it with npm from
  that directory; it has no runtime npm dependencies, so keep it that way.

## Tests

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
