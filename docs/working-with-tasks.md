# Working with Tasks

How we delegate work to Claude Code in this project: tasks are specified as
committed files, executed autonomously in throwaway containers on Git
worktrees, and reviewed as commits. One task = one spec = one branch = one
container session.

The single source of truth for how a spec is written is the
[`/task-spec`](../.claude/commands/task-spec.md) command — this document only
explains **when** to use which path. Day-to-day operations (starting,
resuming, merging, cleaning up, troubleshooting) live in the
[runbook](claude-task-runbook.md).

## Pick the right weight class

Not every task deserves the full ceremony. Decide by cost of a wrong
direction, not by lines of code:

**S — direct prompt, no spec file.**
Script edits, config fixes, single-file changes, anything where writing a
spec would take longer than reviewing the result. Prompt the session
directly, include the verification in the prompt ("run X, iterate until
green, show me the diff").

**M — spec file, no code research needed.**
Features whose requirements you already know (a new filter capability, a UI
component). Run `/task-spec <idea>` in the main worktree on the host — the
command asks clarifying questions, writes `tasks/<name>.md` (English only),
and stops there. Commit the spec on main, then execute.

**L — explore first, then spec.**
Real unknowns: feasibility, architecture decisions, multi-module
restructuring. Start an exploration session in a worktree container with an
enforced read-only gate:

```bash
claude-task feature/<name> --plan
```

Discuss against the real code (Claude cannot modify anything in plan mode),
distill the findings into the spec — `/task-spec` works here too — and make
sure the spec file ends up committed on main before execution starts.

Rule of thumb from experience: vague one-line prompts produce stalled
sessions; invested specs produce first-pass results. If a task feels vague,
it is an M or L, not an S.

## Execute

```bash
git add tasks/<name>.md && git commit -m "docs: add task spec <name>"  # spec first, on main
claude-task feature/<name>                                             # worktree + container
```

First message in the session:

```text
Read tasks/<name>.md and execute the task.
Present a plan first and wait for my approval.
```

Review the plan (the only mandatory checkpoint), approve — then walk away.
Claude implements, verifies against the Definition of Done, and commits
after every verified step. Independent tasks can run in parallel worktrees;
avoid parallel GPU-bound (Ollama) runs.

## Review and merge

Read the final report, but verify it: open the result artifacts, review the
diffs commit by commit, check `git status` in the worktree is clean. Request
corrections in the same session. Then merge and push **from the host** (the
container has no push access), and remove the worktree with
`claude-task --done feature/<name>`. Details and edge cases: runbook.

## Feed lessons back

Every correction you had to make during review is a spec defect. Once per
review, route the lesson to its home: project conventions → `CLAUDE.md`,
spec-writing rules → `/task-spec` checklist, operational procedures →
runbook. That loop — not any single tool — is what keeps first-pass quality
rising.

## Worked example

The credit-rating feature, once done in ~20 interactive prompts, as a single
M-class delegation:

```text
# Task
Add a "credit rating" to Customer: a colored traffic-light component in the
grid (green/yellow/red) indicating creditworthiness.

# Context
- All modules, starting with the non-AI module, then ported to the AI modules
- Conference demo for a banking audience — the implementation should show how
  little code this takes in Vaadin
- Traffic light as a reusable component class, styles in theme CSS

# Procedure
Plan first, wait for approval. Implement module by module; one approval gate
after the first module before porting to the others.

# Definition of Done (beyond CLAUDE.md)
- Demo data contains a realistic mix of good and bad ratings
- Filtering by credit rating works in the AI modules, including two ratings
  at once (OR semantics — covered by a new IT test case)
- Screenshot of the grid showing the traffic light in every module

# Final report
Commits, green tests, open decisions.
```

The OR-semantics bug that originally surfaced only after implementation is a
DoD line here — the executing session finds it itself. That is the pattern:
put what you would have caught in review into the spec instead.
