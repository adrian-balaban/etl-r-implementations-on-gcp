# Răspunsuri K16: Guvernanță enterprise + context MCP

## Task 1: Strategia Classic Prescriptive și costurile ei

**Strategia implicată:**
- Departamentul IT al băncii mandată un stack aprobat: Java 8 · Oracle · WebLogic, cu excepții comitetului.
- **Cost to Make the Grade:** strategia funcționează doar dacă software-ul tratează ca "overhead" și competititorii fac similar.
- **Decision latency:** 7 exception requests wait on committee meetings.
- **"Reduced complexity":** o opțiune marcată pentru cel mai încăldit team încarcă pe ceilalți, complexitatea accidentală.
- **"Low cost":** overengineering-ul e un stânjen continuu care costă mai mult decât licențele salvate.
- **Dissatisfaction:** utilizatorii câștigă dated experiences; echipele nu pot adopta noți noi.

**Analiză clasică:**
Strategia funcționează strict în contexte onde software-ul este treatat ca overhead și competitorii identical. În schimb, pentru o migrație ca ETL-R cu 3 stive și 200k concurrent users, strategia e costlu în accidental complexity.

## Task 2: Recomandare Distributed → Durable Interface

**Ce rămâne guvernat central (integrare):**
- **Identity, security, observability and logging through a mesh sidecar** – departe de responsabilitatea fiecărei echipe, gestionat de echipa de infrastructură.
- **Integration contracts** – topic-urile Kafka (`target-system-target`, `target-system-confirmations`, `target-system-rejections`) sunt interfața migrată, proprietatea centrală.
- **SBOM-uri la build** – exposed la MCP server, nu inventory în ADL. Fiecare build produce SBOM, governance context-l face query.

**Stratigia recomandată:**
1. **Distribuție:** Fiecare echipă poate avea propriul stack tehnologic (Python/Java/etc), dar trebuie să respecte integration contracts.
2. **Interfață durabilă:** Topic-urile Kafka + schema-urile JSON au stabile definiti central – nimic nu schimbă fără coordinare.
3. **Owner mapă:**
   - **Infrastructure team:** identity, security, observability, mesh sidecar
   - **Education systems group:** shared student data APIs (rosters)
   - **Each team:** languages, databases, frameworks for their service + LLMs for essays

## Task 3: SBOM-uri la build vs. ADL inventory

**SBOM (Software Bill of Materials):**
- Generat la fiecare build: `pom.xml` (Java/Maven), `requirements.txt` (Python), `go.mod` (Go).
- Exposed la MCP server la governance context.
- **Nu stoca în ADL:** ADL definește concepte arhitecturale și flow-uri de feedback, nu inventory-uri de pile de tehnologie.
- **Stacks schimbă mai repede decât orice document:** Harvest-from-build files înlocuiește manualul.

**Fitness function MCP example:**
```
ASSERT(SBOM generated in build)
ASSERT(no technology inventory in ADL files)
ASSERT(communication protocols harvested from build SBOM)
```

## Task 4: Push sau pull la contractul Target System

**Contextul actual:**
- Topic-urile Kafka (`target-system-target`, `target-system-confirmations`, `target-system-rejections`) sunt contractul cu sistemul external.
- Cine deține contractul? În ETL-R, `spec/PIPELINE.md` defines the contract, dar implementările îi enforcează.
- **Push vs Pull:**
  - **Push:** Fiecare implementare trimit confirmările la reconciliation (actual flow). Responsabilitatea e la nivel de `load` step.
  - **Pull:** Un consumer independenț (recon service) extrage confirmările la nevoie. Mai flexibil, dar poate introduce întârziere.

**Care rulează verificare?**
- `make conformance-all` rulează comparatorul vs. oracol pe toate implementările.
- MCP tool `get_balance(run_id)` rulează periodic pentru a verifica integritatea cărții deconfirmări.
- **Recomandare:** Modelul hybrid – implementările trimit (push) la reconcilierie, dar un auditor central (MCP) poate extrage (pull) dacă este nevoie pentru debugging.

---
*Generat în cadranul K16 – Guvernanță enterprise + context MCP*