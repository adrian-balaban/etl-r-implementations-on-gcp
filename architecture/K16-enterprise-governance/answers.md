# K16 answers: Enterprise governance + the MCP context

## Task 1: The Classic Prescriptive strategy and its costs

**The strategy at play:**
- The bank's IT department mandates an approved stack: Java 8 · Oracle · WebLogic, with committee-granted exceptions.
- **Cost to Make the Grade:** the strategy works only where software is treated as overhead and the competitors do the same.
- **Decision latency:** 7 exception requests waiting on committee meetings.
- **"Reduced complexity":** one option, marked for the most loaded team — a burden pushed onto everyone else (accidental complexity).
- **"Low cost":** the overengineering is a continuous burden costing more than the saved licences.
- **Dissatisfaction:** the users stay on outdated experiences; the teams cannot adopt new tools.

**The classical analysis:** the strategy works strictly in contexts that treat software as overhead, with identical competitors. For a migration like ETL-R — 3 stacks and 200k concurrent users — it turns expensive through accidental complexity.

## Task 2: The Distributed → Durable Interface recommendation

**What stays centrally governed (integration):**
- **Identity, security, observability and logging through a mesh sidecar** – kept away from each team's responsibility, run by the infrastructure team.
- **Integration contracts** – the Kafka topics (`target-system-target`, `target-system-confirmations`, `target-system-rejections`) are the migrated interface, centrally owned.
- **SBOMs at build time** – exposed at the MCP server, not kept as an ADL inventory. Every build produces the SBOM; the governance context queries it.

**The recommended strategy:**
1. **Distribution:** every team may keep its own tech stack (Python/Java/etc.), but must respect the integration contracts.
2. **A durable interface:** the Kafka topics + the JSON schemas are stable, defined centrally – nothing changes without coordination.
3. **The owner map:**
   - **Infrastructure team:** identity, security, observability, the mesh sidecar
   - **Education systems group:** the shared student data APIs (rosters)
   - **Each team:** languages, databases, frameworks for their own service + LLMs for essays

## Task 3: Build-time SBOMs vs. an ADL inventory

**SBOM (Software Bill of Materials):**
- Generated at every build: `pom.xml` (Java/Maven), `requirements.txt` (Python), `go.mod` (Go).
- Exposed at the MCP server as governance context.
- **Do not store it in the ADL:** the ADL defines architectural concepts and feedback flows, not technology inventories.
- **Stacks change faster than any document:** harvest-from-build (at build time) replaces the manual inventory.

**The fitness-function MCP example:**
```
ASSERT(SBOM generated in build)
ASSERT(no technology inventory in ADL files)
ASSERT(communication protocols harvested from build SBOM)
```

## Task 4: Push or pull for the Target System contract

**The current context:**
- The Kafka topics (`target-system-target`, `target-system-confirmations`, `target-system-rejections`) are the contract with the external system.
- Who owns the contract? In ETL-R, `spec/PIPELINE.md` defines the contract; the implementations enforce it.
- **Push vs. pull:**
  - **Push:** every implementation sends the confirmations to reconciliation (today's flow). The responsibility sits at the `load` step.
  - **Pull:** an independent consumer (the recon service) draws the confirmations on demand. More flexible, but it can introduce delay.

**Who runs the check?**
- `make conformance-all` runs the comparator against the oracle over every implementation.
- The MCP tool `get_balance(run_id)` runs periodically to check the confirmation book's integrity.
- **Recommendation:** a hybrid model – the implementations push to reconciliation, but a central auditor (MCP) can pull when needed for debugging.

---
*Generated in the K16 quadrant — Enterprise governance + the MCP context*