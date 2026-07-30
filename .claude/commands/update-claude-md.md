---
description: Bring CLAUDE.md in line with the repository's actual structure, and change nothing else
---

Update `CLAUDE.md` so that every factual statement in it matches this repository as it actually is
right now. This is the only supported way to change that file: it is deliberately not edited
ad-hoc, because it is the instruction file every future session starts from.

## What to check, in this order

1. **Read `CLAUDE.md` first**, in full, and keep its structure: same sections, same order, same tone,
   same level of detail. You are correcting facts, not rewriting a document.
2. **Module table** — one row per Maven module, in reactor order. Verify against:
   - the root `pom.xml`'s `<modules>` list (which modules exist, and in which order),
   - each module's directory and its `README.md` (what the module actually demonstrates),
   - each module's `src/main/resources/application.properties` (`server.port`).
   Keep the "Approach" wording short enough to stay a table cell; the details belong in the module's
   own README, which the table should point at rather than duplicate.
3. **Non-Maven directories** — anything that is a directory in the repo but deliberately *not* in
   `<modules>` (e.g. the standalone benchmark script). Verify the path, that it has no `pom.xml`, and
   that the reason it stays out of the reactor is still accurate.
4. **Build & run commands** — every command block must work as written for the modules that exist.
5. **Verification / Definition of Done** — the referenced profiles (`-Pit-local-ollama`) and test
   class names must exist. If a class was renamed or split, name what exists now.
6. **Everything else** (conventions, guidelines, "What NOT to do") — leave alone unless a statement
   has become factually false, e.g. because it names a file or module that no longer exists.

## Rules

- **Correct, don't expand.** Do not invent new conventions, new advice or new sections. New rules come
  from the user, not from this command.
- Repository content is written in **English** (this file included), regardless of the chat language.
- Do not touch any other file.
- Do not remove the note that this file is maintained via `/update-claude-md`.
- If a statement in `CLAUDE.md` contradicts the repository and it is not obvious which side is wrong
  (e.g. a convention the code no longer follows), **stop and ask** instead of guessing.

## Finish

Show the resulting diff of `CLAUDE.md` and summarize, in one line per change, what was corrected and
which file or command proves it. If nothing needed changing, say so and leave the file untouched.
