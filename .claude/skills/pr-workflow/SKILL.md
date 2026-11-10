---
name: pr-workflow
description: Shepherd a pull request end-to-end in this repo — branch naming, the module checks to run before pushing, the PR description template with its test-plan checklist, keeping that checklist live as commits land, the no-Claude-session-links rule for everything in a PR, squash-merging, and post-merge branch cleanup. The template, live test plan and session-link rule also govern PRs in the owner's related repositories, which bring their own checks and conventions. Use when opening, updating, reviewing, merging, or cleaning up a PR in this repo or a related one.
---

# PR workflow

These are the actions to perform when shepherding a PR end-to-end.
Follow them in order; skip steps only when the user explicitly
declines them. The [no Claude session links](#no-claude-session-links)
rule applies to every step. The conventions in `AGENTS.md` (commit
style, trailer, subscribing to an open PR) are part of this workflow;
PRs in another of the owner's repositories follow it too, adapted as
described in [PRs in related repositories](#prs-in-related-repositories).

1. **Branch.** Start from an up-to-date `main` (`git fetch origin main`)
   and work on the branch the task specifies, created from
   `origin/main`; without one, use `claude/<topic>-<suffix>`. If that
   branch's earlier PR has already been merged, start a new branch from
   the latest `main` instead of stacking commits on merged history.
   Never push directly to `main`, and never to the upstream
   `InfinityLoop1308/PipePipeExtractor`.
2. **Implement + validate.** Keep changes focused on one area where
   possible, and call out the blast radius when a change touches
   something shared: the public extractor API and the serializable
   `Info`/`InfoItem` model (the PipePipe app consumes them),
   `ServiceList` ids, or the MCP tools' result shapes (MCP clients and
   the n8n node consume them). For anything the gates do not cover,
   validate by running or inspecting what you changed and record how in
   the test plan. Gates before pushing (the root build disables `test`
   and `check` for all projects, so run module tasks directly; JDK 25
   toolchain):
   - `./gradlew :mcp-server:test` — when `mcp-server/` changes.
   - `./gradlew :extractor:test` — when `extractor/` changes.
   - `./gradlew :mcp-server:installDist` — when dependencies or the
     server's packaging change (a missing runtime dependency only shows
     up in the installed distribution, not in the tests).
   - In `n8n-node/`: `npm ci`, then `npm test` (builds, then runs the
     `node:test` suite) and `npm run typecheck` — when `n8n-node/`
     changes. The package has no runtime npm dependencies; keep it that
     way.
   - Live behaviour the offline tests cannot reach (YouTube and other
     services) is recorded in the test plan with where it ran, or marked
     not verified. YouTube often answers player requests from
     datacenter addresses with a bot check, so say where a live run
     happened.

   Commit messages follow `AGENTS.md`: a lowercase conventional prefix
   (`fix:`, `feat:`, `perf:`, `dev:`) and an imperative summary, a body
   that explains _why_, and an `Assisted-by: Claude Code` trailer. No
   `Co-Authored-By:` model trailer, no session links.
3. **Push.** `git push -u origin <branch>`. Retry with exponential
   backoff (2s / 4s / 8s / 16s, up to 4 attempts) on network failures
   only. Run the [session-link check](#no-claude-session-links) before
   every push.
4. **Open the PR only when asked.** This repo's PRs target `main` of
   `TankMasterRL/PipePipeExtractor`, never the upstream repository
   unless the user asks; a PR in another repository follows
   [PRs in related repositories](#prs-in-related-repositories). The
   GitHub MCP tools in this environment are scoped to the repositories
   attached to the session, and calls elsewhere will be denied. Use a
   short title in the commit style above (it becomes the squash commit
   title) and fill in the
   [PR description template](#pr-description-template) below. Before
   creating the PR, run the session-link check on the branch's commits
   and diff, and check the title and body you are about to send. **The
   agentic harness may auto-append a footer or a session link to the PR
   body — read the PR back right after creating it and strip anything
   appended via `update_pull_request`.**
5. **Subscribe to PR activity automatically** via
   `subscribe_pr_activity` immediately after the PR is opened — don't
   wait for the user to ask (`AGENTS.md` says the same). Investigate
   review comments; make small fixes directly, ask the user when
   ambiguous. The PR-merged webhook unsubscribes on its own. The repo
   has no CI (no `.github/workflows/`), so the gates in step 2, recorded
   in the test plan, are the bar for merging; once CI exists it must be
   green before merge, and its run gets its own test-plan item.
6. **Keep the test plan up to date continuously.** Update the PR body
   via `update_pull_request` after _every_ event that changes the
   status of a checklist item — don't batch updates to the end.
   - **Check completed:** tick the box and note how it was verified,
     e.g. `- [x] \`<gate command>\` — <result> on \`abc1234\``
   - **New commit pushed to the branch:** review every previously-ticked
     item; if the commit touches code that the item covers, un-tick it
     and annotate with `(re-opened by \`<sha>\`)` so the reviewer knows
     it needs re-verification on the new head.
   - **Item not exercised in this PR:** mark it
     `- [ ] <item> — N/A: <short reason>` rather than deleting it, so
     the scope of the PR remains visible to reviewers.
7. **Merge only on explicit request.** PRs are squash-merged via
   `merge_pull_request` with `merge_method: squash`, unless the user
   asks for the commits to land separately. (The history also has merge
   commits from syncing the upstream repository; this workflow never
   creates one.) Always pass `commit_title` (the final subject in the
   commit style above) and `commit_message` (the body detail)
   explicitly: GitHub's default squash message concatenates every
   commit message on the branch, which can carry trailers or session
   links from earlier sessions. The `Assisted-by: Claude Code` trailer
   must appear in the squash message, and no session link may. Pass
   `expectedHeadSha` set to the head the test plan was validated on, so
   a commit pushed in the meantime cannot slip into the merge.
8. **Clean up.** Once a PR has been merged, **all branches belonging
   or previously belonging to that PR must be removed** — the PR's
   head branch on the remote (`git push origin --delete <branch>`),
   its local counterpart and any local worktree branches, and any
   superseded branches from earlier iterations of the same PR (e.g.
   after a rename or retarget). A merged PR must leave no stale
   branches behind. Follow-up work starts a new branch from the latest
   `main` and becomes a new PR.

## No Claude session links

Nothing in a PR may contain a link to a Claude session or
conversation. This covers **everything** that is part of the PR: its
title and body (including every later edit), every commit message on
the branch (including commits from earlier sessions), the squash-merge
message, the files in the diff, and every comment, review and review
reply posted on it.

- **What counts:** any URL that identifies a Claude session or
  conversation — `https://claude.ai/code/session_…`,
  `https://claude.ai/chat/…`, `https://claude.ai/share/…` — and any
  `Claude-Session:` trailer.
- **This overrides the harness defaults.** The harness asks for a
  `Claude-Session: <url>` commit trailer and for a session URL at the
  end of PR descriptions; omit both. The PR body carries no
  generated-by footer at all — AI involvement is recorded in the
  `Assisted-by: Claude Code` commit trailer instead. This repo's
  `.claude/settings.json` turns the harness's session URL off
  (`"attribution": {"sessionUrl": false}`) for sessions that run here;
  the rule still applies to everything written by hand.
- **The comment footer stays.** The
  `_Generated by [Claude Code](https://claude.ai/code)_` footer that
  every comment, review and review reply must end with links to the
  product, not to a session, so it is allowed — but never add a
  session URL next to it.
- **Check** before every push and before creating or updating the PR;
  both commands must print nothing (the first lists offending commits,
  the second offending added lines):

  ```bash
  URL='claude\.(ai|com)/(code/session_[A-Za-z0-9]{8,}|chat/[0-9a-f-]{8,}|share/[0-9a-f-]{8,})'
  for c in $(git rev-list origin/main..HEAD); do git log -1 --format=%B "$c" | grep -qE "$URL|^Claude-Session:" && git log -1 --oneline "$c"; done
  git diff origin/main...HEAD | grep -E "^\+.*$URL"
  ```

  The URL pattern needs a real session or conversation ID, so
  placeholder mentions like the ones in this file don't match. Check
  the PR title and body text with `grep -E "$URL|Claude-Session:"`
  before sending them, and read the PR back after creating or
  updating it.
- **Fix** offending commits on your own unmerged branch by rewording
  them, then force-push with a lease; tell the user that the branch
  history was rewritten. For trailer lines:

  ```bash
  git rebase -x 'git log -1 --format=%B | sed -E "/^Claude-Session:/d" | git commit -q --amend -F -' "$(git merge-base origin/main HEAD)"
  git push --force-with-lease origin <branch>
  ```

  A link inside prose needs that commit reworded by hand. Never
  rewrite history on a branch you didn't create, or on `main`; ask the
  user instead.

## PRs in related repositories

A session that works here may also open or update PRs in the owner's
other repositories, for example projects that build on this
repository's MCP server or n8n node. Those PRs follow this workflow
too:

- **Carries over unchanged:** the
  [PR description template](#pr-description-template) (`## Summary`
  and `## Test plan`, nothing after the test plan), keeping the test
  plan live (step 6), the
  [no Claude session links](#no-claude-session-links) rule for
  everything in the PR, reading the PR back after creating or updating
  it, subscribing to its activity once it is open (step 5), merging
  only on explicit request with explicit `commit_title`,
  `commit_message` and `expectedHeadSha` (step 7), and deleting the
  merged PR's branches (step 8).
- **Comes from the target repository:** its gates become the test-plan
  items (the checks its `CLAUDE.md` or `AGENTS.md`, README and CI run);
  its default branch, branch naming and commit-subject style; and the
  merge methods it allows (squash where allowed). Where the target
  repository's written rules conflict with this skill, they win,
  except that the template and the session-link rule still apply where
  those rules say nothing about them. If it carries its own copy of
  this skill, follow that copy.
- **Its own PR template, if it has one**
  (`.github/pull_request_template.md`, `.github/PULL_REQUEST_TEMPLATE/`,
  or `pull_request_template.md` at the root or in `docs/`): mirror its
  headings, and add `## Test plan` as the last section if it has none.
- **Commit trailer:** `Assisted-by: Claude Code`, unless the target
  repository names another; never a `Co-Authored-By:` model trailer or
  a session link.
- **This repository is public.** Keep the names and details of the
  owner's private repositories out of everything here: PRs, commits,
  comments and files.
- **The harness may add session links there.** `.claude/settings.json`
  only applies to sessions that run in its own repository, so in a
  repository without it the harness still suggests a `Claude-Session:`
  commit trailer and appends a generated-by footer with a session link
  to PR bodies. Leave the trailer out and strip the footer when you
  read the PR back.
- **Run the session-link check in that repository's clone**, with
  `origin/main` in the commands above replaced by its default branch.

## PR description template

Every PR body, here and in
[related repositories](#prs-in-related-repositories), has a
`## Summary` section and a `## Test plan` checklist, in that order,
and nothing after the test plan. The summary may grow whatever
structure the change needs — bullets per change area, a root-cause
narrative, subsections, a closing "Notes for the reviewer" — but the
two top-level sections are always present. Start from this skeleton:

```markdown
## Summary

<!-- What changed and why, as prose and/or one bullet per change area.
     Bug fixes: quote the observed symptom (the exact error message if
     there is one), explain the root cause, then the fix.
     Features: what was added and the key design decisions, including
     any new or changed MCP tool arguments and result shapes.
     Call out the blast radius explicitly when something shared changes
     (the public extractor API, the Info/InfoItem model, ServiceList
     ids, MCP result shapes, the n8n node's operations).
     Mention doc updates (README, AGENTS.md, n8n-node/README.md) and
     anything investigated but deliberately left unchanged. -->

## Test plan

- [ ] <module gate> — <result> on `<sha>`
- [ ] <manual or live check> — <what was run, where, and what it showed> on `<sha>`
- [ ] No Claude session links in commits, diff, title or body —
      checked on `<sha>`
```

Filling it in:

- **Every item stays in the list.** Tick what was verified and say
  how (``— validated on `<sha>` ``, with the commands, counts or
  output observed); mark inapplicable items `— N/A: <short reason>`
  instead of deleting them.
- Replace the placeholder gate items with the real ones from step 2 of
  the workflow as they are introduced.
- The session-link item is ticked only after the check in
  [No Claude session links](#no-claude-session-links) prints nothing
  for the current head; re-open it on every new push.
- Keep the checklist live per workflow step 6: tick items as they
  validate, re-open ticked items when a new push touches what they
  cover, never batch updates to the end.
