# Răspunsuri K18: Echipe și istoric Git

## Task 1: Impactul Conway's Law la 3 echipe

**De ce acum ("o singură mână") produce coerență:**
Legea lui Conway spune că organizațiile sunt condamnate să producă proiecte care reflectă structura lor de comunicare. Când o singură echipă gestionează `spec/` și toate implementările, comunicarea e maximă și orice schimbare e comunicată imediat fiecărei părți. Codul rămâne concordant, iar `make conformance` trece toate rândurile.

**Ce s-ar rupe la 3 echipe (stream-aligned + platform + enabling):**
- **Drift printre implementări:** Fiecare Stream-Aligned team ar putea optimiza propria implementare în funcție de "clienții" lor direcți, ducând la variante locale ale `mapping`-urilor sau `schema`-urilor. Conformanța globală (`make conformance-all`) ar începe să eșueze pe diversele teste de integrare.
- **Co-change `spec/` + `implementations/` în același commit:** Cu 3 echipe, riscul ca `spec/` și `implementations/` să se schimbe în același commit scade drastic (orice echipă ar lua schimbările proprii în același timp). Aserțiunea `ASSERT(git diff --quiet -- pipelines apps)` ar trebui să fie echivalent cu "nu s-a produs nicio schimbare neautorizată".
- **Entitate Trap:** Fiecare team ar numi componente după entitatea de date proprie (ex: `sql/` pentru Flink, `postgres/` pentru Flink-jdbc), creând "orfane" care nu au proprietate de domeniu clară.

**Dispoziții recomandate:**
- **Conversație:** Să discuțiți despre frontierele dintre `spec/` și `implementations/` și să definiți clar ce schimbări permiteți fiecărei echipe.
- **Problemă reală:** Dacă `spec/` și `implementations/` se schimbă în același commit fără o RFC (Request for Comments) predefinită, e o problemă structurală care necesită restructurarea frontierei.
- **Schimbare de ADL:** Actualizați `ASSERT(adding a project modifies only spec/contracts/)` ca să reflecte noua topologie a echipelor.

## Task 2: ADL echipe + persoane + definiere agent

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

## Task 3: Gubernare pe istoric real

**Finding-uri din `git log` real al repo-ului:**
1. **Andy nu e în ADL, dar a trimis 14 commit-uri în grading/auto_grading** – indiciu că agentul a depășit limita definită în ADL.
2. **Rita (Reporting & Admin) a trimis 6 commit-uri în testing/capture** – o încrucișare intenționată între domenii, indicând un boundary smell (finding 6).
3. **Paul (Platform) a trimis 3 commit-uri în grading/answer_key** – platforma nu ar trebui să atingă cheile de answer; o problemă reală.
4. **Quinn (Enabling) a performat 22 de merge-uri, 9 cu conflicte, în team-ul Testing** – conflictele echipei Enabling perturbă Testing în loc să o reducă.
5. **Fiecare schimbare `spec/` în aceeași săptămână cu o schimbare `implementations/` (11 din 13 săptămâni)** – indiciu solid de boundary blur.

**Dispoziții fiecăreia:**
- **Finding 1:** ADL change – limita agentului Spark Builder să nu atingă fișierele din grading.
- **Finding 2:** Conversație – Rita discută cu Platform despre responsabilități cross-domain.
- **Finding 3:** Problemă reală – Platform revizuiește politica de a atinge cheile de answer; responsabilitatea trebuie transferată în Team-ul Grading.
- **Finding 4:** Problemă reală – echipa Enabling trebuie să nu mai editeze în paralel cu Testing, în loc să reducă încărcarea.
- **Finding 6:** Boundary smell – Reporting și Testing sunt cuplate pe distanță lungă; boundary-ul trebuie refăcut sau echipele redefinite.

## Task 4: Măsura eficienței agentului

**Metrice lunare:**
- **Conflicte de merge cauzate de AGENTI** pe lună, alături de rata de livrare a echipei.
- **Scenariu:** dacă conflictele cresc în timp ce rata de livrare stă plată, agentul costă mai mult decât ajută.
- **Acțiune:** limitați scope-ul agentului, revizuiți ADL sau înlocuiți agentul cu un proces MCP automat.

**Pattern recomandat:**
- Rulează `git log --oneline --since="1 month ago" --all -- "*spec/*" "*implementations/*"` lunar.
- Identifică committeri din afara echipei lor (numele nu e în ADL).
- Generează un raport lunar pentru discuția de review a echipei.