# Task
Merge modules 01 and 02 into a single module "01-non-ai-filter". The new module should contain two views: one for in-memory filtering using Java methods and one for lazy loading with specification-based database filtering. Both views should be covered by BrowserlessTests.

# Context
- Affected modules: `01-simple-filter`, `02-lazy-filter` (will be merged into `01-non-ai-filter`)
- Purpose: Clear separation between non-AI-based filtering approaches (In-Memory vs. Lazy Loading) and AI-based approaches
- Relevant existing parts:
  - `01-simple-filter`: In-memory filtering with text field
  - `02-lazy-filter`: Lazy loading with DataProvider and text fields below column headers

# Approach
1. First create a plan (Plan Mode) and show it to me. Wait for my OK.
2. Implement the plan. Work in logical steps, commit after each verified step
   (Conventional Commits, no push).
3. Verify autonomously according to Definition of Done in CLAUDE.md.
   Iterate on errors independently — don't present error messages for analysis
   that you can reproduce and fix yourself.
4. Do you see any risks or gaps in the assignment?

# Definition of Done (in addition to CLAUDE.md)
## Functional Requirements
- Module `01-non-ai-filter` exists with correct artifact ID and adapted class names
- View 1 (Landing Page, Route alias "in-memory"):
  - Displays all Customer data from database (loaded in-memory)
  - Filtering with a single text field using Java methods
  - Sorting works
- View 2 (Route "lazy"):
  - Displays Customer data with lazy loading (DataProvider)
  - Text fields below each column header in the Grid
  - On change, a JPA Specification is created and database query executed
  - Sorting works
  - Multiple filters simultaneously possible

## Test Requirements
- BrowserlessTest for View 1 (In-Memory):
  - All data is displayed initially
  - Sorting works
  - Filter by specific persons works
  - Filter by yesterday's date works
  - Filter by city "Berlin" works

- BrowserlessTest for View 2 (Lazy Loading):
  - All data is displayed initially
  - Sorting works
  - Individual filters work (person, date, city)
  - Combined filters work:
    - By name and city simultaneously
    - By yesterday's date and positive creditworthiness simultaneously

## Verification
- `./mvnw verify -pl 01-non-ai-filter` passes
- Both old modules (`01-simple-filter`, `02-lazy-filter`) are removed
- README.md and CLAUDE.md are updated and reflect the new module structure
- All outdated references to the old modules are removed

# Final Report
Summarize at the end: what was changed (per commit), which tests are green,
open points/decisions that I need to make.