# Răspunsuri K18: Echipe și istoric Git

## Task 1: Impactul Conway's Law la 3 echipe

**De ce acum ("o singură mâna") produce coerență:**
Legea lui Conway stăte că organizele sunt condamnate să producă proiecte care reflectă structura lor de comunicare. În momentul în care avem o singură echipă care gestionează `spec/` și toate implementările, comunicarea este maximă și orice schimbare este immediately communicated fiecarei părți. Codul rămâne concordant, iar `make conformance` trece toate rândurile.

**Ce s-ar rupe la 3 echipe (stream-aligned + platform + enabling):**
- **Drift printre implementări:** Fiecare Stream-Aligned team ar putea optimiza propria implementare în funcție de "clienții" lor directe, duceând la varianți locale ale `mapping-urilor` sau `schema-urilor`. Conformanța globală (`make conformance-all`) ar începe să eșueze pe diversele teste de integrare.
- **Co-change `spec/` + `implementations/` în același commit:** Cu 3 echipe, riscul ca `spec/` și `implementations/` să schimbere în același commit scade drastic (orice echipă ar lua schimbările proprii în același timp). Aserțiunea `ASSERT(git diff --quiet -- pipelines apps)` ar trebui să fie echivalent cu "nu s-a produs nicio schimbare neautorizată".
- **Entitate Trap:** Fiecare team ar numi componente după entitatea de date proprie (ex: `sql/` pentru Flink, `postgres/` pentru Flink-jdbc), creând "orfane" care nu au proprietate domeniu clară.

**Dispoziții recomandate:**
- **Conversație:** Să discuțiți despre frontierile antarctice dintre `spec/` și `implementations/` și să definiți clar ce schimbări permit fiecărei echipă.
- **Problemă reală:** Dacă `spec/` și `implementations/` schimbă în același commit fără o RFC (Request for Comments) predefinită, e o problemă structurală care necesită restructurarea frontierei.
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
1. **Andy, nu e în ADL, a trimis 14 commit-uri în grading/auto_grading** – indiciu că agentul a depășit limita sa definită în ADL.
2. **Rita (Reporting & Admin) a trimis 6 commit-uri în testing/capture** – cruce iméntională domenială, indica bord smell (finding 6).
3. **Paul (Platform) a trimis 3 commit-uri în grading/answer_key** – platforma tocmai nu ar trebui să touchete cheile de answer, o problemă reală.
4. **Quinn (Enabling) a performat 22 merge-uri, 9 conflict-uri, în team-ul Testing** – konfliktii ale enabling-ului perturbă Testing în loc să o reduz.
5. **Fiecare schimbare `spec/` în aceeași săptămână cu o schimbare `implementations/` (11 din 13 săptămâni)** – indiciu solid de boundary blur.

**Dispoziții fiecăreia:**
- **Finding 1:** ADL change – limita agentului Spark Builder să nuaugeă fișierele din grading.
- **Finding 2:** Conversație – Rita discute cu Platform despre responsabilități cross-domain.
- **Finding 3:** Problemă reală – Platformul revizie politica de a touchete chei de answer; responsabilitatea trebuie transferată în Team Grading.
- **Finding 4:** Real problem – Enable team-ul trebuie să se antreneze să nu editeze în paralel Testing în loc să reducă încărcarea.
- **Finding 6:** Boundary smell – Reporting și Testing sunt coupleate pe distanță lungă; boundary-ul trebuie refosit sau echipele redefine.

## Task 4: Măsura eficienței agentului

**Metrice lunare:**
- **Conflicte de merge cauzate de AGENTI** pe lună lună, alongside rata de livrare a echipei.
- **Scenariu:** dacă conflictele cresc cu o rata flată de delivery, agentul costă mai mult decât ajută.
- **Acțiune:** limita scope-ul agentului, revizia ADL, sau înlocuirea agentului cu un proces MCP automat.

**Pattern recomandat:**
- Rulează `git log --oneline --since="1 month ago" --all -- "*spec/*" "*implementations/*"` lunar.
- Identifică commit-eri din afară echipei sale (committer name nu este în listă).
- Generează un raport lunar pentru discuția de review a echipei.