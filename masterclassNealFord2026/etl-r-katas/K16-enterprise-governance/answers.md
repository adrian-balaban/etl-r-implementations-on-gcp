# Răspunsuri K16: Guvernanță enterprise + context MCP

## Task 1: Strategia Classic Prescriptive și costurile ei

**Strategia implicată:**
- Departamentul IT al băncii impune un stack aprobat: Java 8 · Oracle · WebLogic, cu excepții de la comitet.
- **Cost to Make the Grade:** strategia funcționează doar dacă software-ul e tratat ca overhead și competitorii fac la fel.
- **Decision latency:** 7 exception requests wait on committee meetings.
- **"Reduced complexity":** o opțiune marcată pentru echipa cea mai încărcată, care pune povara pe ceilalți (complexitate accidentală).
- **"Low cost":** overengineering-ul e o povară continuă care costă mai mult decât licențele economisite.
- **Dissatisfaction:** utilizatorii rămân cu experiențe învechite; echipele nu pot adopta unelte noi.

**Analiză clasică:** Strategia funcționează strict în contexte în care software-ul e tratat ca overhead și competitorii sunt identici. În schimb, pentru o migrație ca ETL-R cu 3 stive și 200k concurrent users, strategia e costisitoare în complexitate accidentală.

## Task 2: Recomandare Distributed → Durable Interface

**Ce rămâne guvernat central (integrare):**
- **Identity, security, observability and logging through a mesh sidecar** – departe de responsabilitatea fiecărei echipe, gestionat de echipa de infrastructură.
- **Integration contracts** – topic-urile Kafka (`target-system-target`, `target-system-confirmations`, `target-system-rejections`) sunt interfața migrată, proprietatea centrală.
- **SBOM-uri la build** – exposed la MCP server, nu inventory în ADL. Fiecare build produce SBOM, governance context-l face query.

**Strategia recomandată:**
1. **Distribuție:** Fiecare echipă poate avea propriul stack tehnologic (Python/Java/etc), dar trebuie să respecte integration contracts.
2. **Interfață durabilă:** Topic-urile Kafka + schema-urile JSON sunt stabile, definite central – nimic nu se schimbă fără coordonare.
3. **Owner mapă:**
   - **Infrastructure team:** identity, security, observability, mesh sidecar
   - **Education systems group:** shared student data APIs (rosters)
   - **Each team:** languages, databases, frameworks for their service + LLMs for essays

## Task 3: SBOM-uri la build vs. ADL inventory

**SBOM (Software Bill of Materials):**
- Generat la fiecare build: `pom.xml` (Java/Maven), `requirements.txt` (Python), `go.mod` (Go).
- Exposed la MCP server la governance context.
- **Nu stoca în ADL:** ADL definește concepte arhitecturale și flow-uri de feedback, nu inventare de stive de tehnologie.
- **Stivele se schimbă mai repede decât orice document:** harvest-from-build (la build) înlocuiește inventarul manual.

**Fitness function MCP example:**
```
ASSERT(SBOM generated in build)
ASSERT(no technology inventory in ADL files)
ASSERT(communication protocols harvested from build SBOM)
```

## Task 4: Push sau pull la contractul Target System

**Contextul actual:**
- Topic-urile Kafka (`target-system-target`, `target-system-confirmations`, `target-system-rejections`) sunt contractul cu sistemul external.
- Cine deține contractul? În ETL-R, `spec/PIPELINE.md` definește contractul, dar implementările îl impun.
- **Push vs Pull:**
  - **Push:** Fiecare implementare trimite confirmările la reconciliation (actual flow). Responsabilitatea e la nivel de `load` step.
  - **Pull:** Un consumer independent (recon service) extrage confirmările la nevoie. Mai flexibil, dar poate introduce întârziere.

**Care rulează verificare?**
- `make conformance-all` rulează comparatorul vs. oracol pe toate implementările.
- MCP tool `get_balance(run_id)` rulează periodic pentru a verifica integritatea cărții de confirmări.
- **Recomandare:** Modelul hybrid – implementările trimit (push) la reconciliere, dar un auditor central (MCP) poate extrage (pull) dacă este nevoie pentru debugging.

---
*Generat în cadranul K16 – Guvernanță enterprise + context MCP*