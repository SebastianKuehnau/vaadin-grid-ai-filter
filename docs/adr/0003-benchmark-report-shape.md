# 3. The benchmark report is verdict-first, with capped prose and uncapped tables

Date: 2026-08-24

## Status

Accepted

## Context

`benchmark/report-prompt.md` asked for a correctness table, a cost table, a section per variant and a
list of findings. Against two models and four variants that produced a 284-line report in which the
two conclusions that mattered — that the more expressive tool made the weaker model worse, and that
04 buys 03's capability at roughly 2.4× the tokens — sat below ten tables and a methodology preamble.

The complaint was "too long and I cannot find the relevant data". Measuring it showed the diagnosis
was wrong in a useful way: the prompt is 41 lines, and the length came almost entirely from **prose**
that retold numbers already standing in the tables. The tables were not the problem; they were the
answer.

Two further things were found while re-reading the pipeline:

- The report step ran with `--allowedTools Read Write Glob Grep`. It therefore computed medians over
  two dozen logs without being able to run anything — the one part of the job least suited to being
  done in a model's head.
- The `enabled` counts per variant were hardcoded in the prompt, a second copy sat in
  `benchmark/README.md`, and the real source is the capability table in
  `docs/canonical-query-set.md`. The definition of done requires a new query to be added to every
  module's IT classes, which silently invalidates every hardcoded copy.

## Decision

**The report answers one question**: which invocation method performs best, and which model/method
combination performs best — by correctness, latency and token cost. It is not a regression report
between runs and not a diagnosis of one variant.

**Verdict first, in a fixed five-item shape** (recommendation, best invocation method, best model,
surprise, do not demo live), so it is scannable across runs instead of re-invented each time. The
"best invocation method" item is the only place allowed to explain mechanism, and gets up to three
sentences for it; the per-variant prose section is gone.

**Prose is capped, tables are not.** Tables are data and grow with the number of models; prose is
what grows without bound. The binding rule is: *never repeat a number in prose that already stands in
a table.* Everything except the verdict, the two tables and the failure list lives below a horizontal
rule as an appendix.

**Two tables**, mirroring the two halves of the question: one row per variant with both models
pooled, then one row per model and variant **grouped by variant**, not by model. Grouping by model
forces the reader to collect one method across four blocks, which was the actual findability
complaint. Winners are marked only among full-reach rows, because the cheapest row is otherwise the
one that attempted the fewest queries.

**`Bash` is added to the report step's allowed tools.** The prompt asks for a throwaway parser and
for the parsed rows to be written to `report-data.json`, one object per model call, so the tables can
be recomputed and audited without re-reading the logs.

**`Test Reach` is derived from the logs** (`OK + FAIL`) and cross-checked against
`docs/canonical-query-set.md`, with a disagreement reported rather than resolved.

**Metrics**: Test Reach, Pass, median latency, tokens and calls per query, model size. Tokens per
second is not reported — it is close to a constant per model and does not separate the invocation
methods, which is what the report is for. Time to first token is not reported either: nothing in this
harness streams, and comparing a first token against a completed tool call would not mean anything.

## Consequences

The report drops from ~284 to ~106 lines against two models, with nothing removed that was asked
for — the appendix keeps the totals, the prompt sizes and the methodology, only lower down. Per-query
token and time tables are gone from the prose entirely and live in `report-data.json` instead.

Allowing `Bash` widens what the report step may do inside a committed script. The rule in `CLAUDE.md`
that no bash parses a log still holds as written: no *committed* bash parses one. What changed is that
the agent may write an uncommitted one for the duration of the report.

`benchmark/README.md` keeps its `enabled` snapshot for orientation, but now says explicitly that the
report does not read it.

The five-item verdict is a fixed shape a weak model may fill badly. Nothing enforces it, the same way
nothing enforces the query-set table of ADR 0001 — the prompt is the only gate, and a report that
comes out shapeless is a signal to fix the prompt rather than the report.
