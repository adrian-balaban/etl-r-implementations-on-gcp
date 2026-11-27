# K18 answers: Teams and Git history

## Task 1: Conway's Law at 3 teams

**Why "one pair of hands" now produces coherence:**
Conway's Law says organizations are doomed to produce designs that mirror their communication structure. When one team owns `spec/` and every implementation, communication is maximal and every change is announced to every part at once. The code stays aligned, and `make conformance` passes row by row.

**What would break with 3 teams (stream-aligned + platform + enabling):**
- **Drift between implementations:** each Stream-Aligned team could optimize its own implementation for its direct "clients", producing local variants of the `mappings` or `schemas`. Global conformance (`make conformance-all`) would start failing across integration tests.
- **`spec/` + `implementations/` co-changes in one commit:** with 3 teams, the risk of spec and implementations changing in the same commit drops sharply (each team takes its own changes in its own time). `ASSERT(git diff --quiet -- pipelines apps)` should mean "no unauthorized change happened".
- **The Entity Trap:** each team would name components after its own data entity (e.g. `sql/` for Flink, `postgres/` for Flink-jdbc), creating "orphans" with no clear domain owner.

**The recommended dispositions:**
- **A conversation:** discuss the spec/implementation boundaries and define clearly which changes each team is allowed.
- **A real problem:** if spec and implementations change in one commit without a predefined RFC, that is a structural problem requiring a boundary rework.
- **An ADL change:** update `ASSERT(adding a project modifies only spec/contracts/)` to reflect the new team topology.

## Task 2: The team ADL: people + the agent defined as a member

```
DESCRIPTION Define team structure for ETL-R governance
CATEGORY Team Structure
DEFINE SYSTEM ETL-R AS etl-r-implementations-on-gcp
     DEFINE TEAM Platform AS Platform Team
          ASSIGN DOMAIN spec/ contracts/
          DEFINE PERSON Alice AS alice@company.com
               ROLE: Architecture Lead
          DEFINE AGENT Tina AS agent-tina@company.com
               SCOPE: spec/ only
     DEFINE TEAM Flink AS Stream-Aligned Team
          ASSIGN DOMAIN implementations/flink/
          DEFINE PERSON Bob AS bob@company.com
          DEFINE AGENT Max AS agent-max@company.com
               SCOPE: flink implementation only
     DEFINE TEAM Beam AS Stream-Aligned Team
          ASSIGN DOMAIN implementations/beam-dataflow/
          DEFINE PERSON Carol AS carol@company.com
          DEFINE AGENT Zoe AS agent-zoe@company.com
               SCOPE: beam implementation only
     DEFINE TEAM TargetSystem AS Target System Team
          ASSIGN DOMAIN target-system-mock/
          DEFINE PERSON Dave AS dave@company.com
     DEFINE SUPERUSER Team AS Super User Team
          DEFINE PERSON Eve AS eve@company.com
               ROLE: anywhere, especially emergencies
     DEFINE ALUMNI Team AS Alumni Team
          DEFINE RETIRED_PERSON Frank AS frank@retired.com
               STILL IN commit history
ASSERT(TEAM MEMBERS include PERSONS and AGENTS)
ASSERT(No commits from anyone other than TEAM MEMBERS)
ASSERT(TEAM MEMBERS in Stream-Aligned Teams cannot commit files outside their domains)
ASSERT(Super User Teams can commit files belonging to other teams)
ASSERT(No merge conflicts within Stream-Aligned Teams caused by AGENTS within the past month)
```

## Task 3: Governance on real history

**The findings from the repo's real `git log`:**
1. **Andy is not in the ADL, yet sent 14 commits into grading/auto_grading** – a sign the agent exceeded the limit defined in the ADL.
2. **Rita (Reporting & Admin) sent 6 commits into testing/capture** – an intentional cross-domain move, indicating a boundary smell (finding 6).
3. **Paul (Platform) sent 3 commits into grading/answer_key** – the platform should not touch answer keys; a real problem.
4. **Quinn (Enabling) performed 22 merges, 9 with conflicts, inside the Testing team** – the Enabling team's conflicts disturb Testing instead of relieving it.
5. **Every `spec/` change in the same week as an `implementations/` change (11 of 13 weeks)** – a solid sign of boundary blur.

**The disposition for each:**
- **Finding 1:** an ADL change – the Spark Builder agent's limits must not touch grading files.
- **Finding 2:** a conversation – Rita talks with Platform about cross-domain responsibilities.
- **Finding 3:** a real problem – Platform reviews its policy of touching answer keys; the responsibility should move to the Grading team.
- **Finding 4:** a real problem – the Enabling team must stop editing in parallel with Testing, instead of adding to the load.
- **Finding 6:** a boundary smell – Reporting and Testing are coupled at long range; the boundary must be redrawn, or the teams redefined.

## Task 4: Measuring the agent's effectiveness

**Monthly metrics:**
- **Merge conflicts caused by AGENTS** per month, alongside the team's delivery rate.
- **The scenario:** if conflicts grow while the delivery rate stays flat, the agent costs more than it helps.
- **The action:** bound the agent's scope, revise the ADL, or replace the agent with an automated MCP process.

**The recommended pattern:**
- Run `git log --oneline --since="1 month ago" --all -- "*spec/*" "*implementations/*"` monthly.
- Identify committers outside their team (names not in the ADL).
- Generate a monthly report for the team's review discussion.