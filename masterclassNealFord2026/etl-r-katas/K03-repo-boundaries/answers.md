# Răspunsuri K03: Frontiere între implementări în monorepo

## Task 1: ADL explicit vs. implicit

```
DESCRIPTION Define monorepo service boundaries
CATEGORY Structural
DEFINE SYSTEM ETL-R AS etl-r-implementations-on-gcp
     DEFINE SERVICE flink AS implementations/flink
     DEFINE SERVICE flink-jdbc AS implementations/flink-jdbc
     DEFINE SERVICE beam-dataflow AS implementations/beam-dataflow
     DEFINE LIBRARY Contracts AS spec/contracts
ASSERT(flink HAS NO DEPENDENCY ON flink-jdbc, beam-dataflow)
ASSERT(flink-jdbc HAS NO DEPENDENCY ON flink, beam-dataflow)
ASSERT(beam-dataflow HAS NO DEPENDENCY ON flink, flink-jdbc)
ASSERT(no cycles exist between services in ETL-R)
```

**Compară variantele:**

| Varianta | Sintaxă | Ce prinde | Ce ratează |
|---|---|---|---|
| **Explicit** | `ASSERT(flink HAS NO DEPENDENCY ON flink-jdbc, beam-dataflow)` | Orice nouă dependență inter-implementare adăugată de agent | Nu prinde altă dependență neanticipată (de exemplu, o dependență pe un nou service care nu este enumerat în listă) |
| **Implicit** | `ASSERT({flink, flink-jdbc, beam-dataflow} HAVE NO DEPENDENCIES ON OTHER SERVICES)` | Orice dependență nouă, inclusiv cele neprevăzute | Prea strict: împiedică partajarea intenționată (de exemplu, `artefacts.json` partajat) |

**Care prinde agentul?** Testul `verify-project2` (care rulează `git diff --quiet -- pipelines apps`) prinde implicit, pentru că orice modificare în `pipelines/` sau `apps/` va pică build-ul. ADL explicit prinde doar dependențele enumerate.

## Task 2: Pseudocodul regulii de ciclu

```ada
FOREACH COMPONENT c IN system DO
    ASSERT(no cycles exist between c and other components)
END
```

**Ce se întâmplă:** Agentul adaugă auto-import pentru clase între pachete, creând un ciclu `A → B → C → A`. Regula de mai sus iterează prin fiecare componentă și verifică că nu există ciclu către alte componente. Dacă ciclul există, aserțiunea pică și fitness function-ul raportează un "structural violation".

**Fitness function rulabil (Python/pytest-example):**
```python
def test_no_component_cycles(import_helper):
    components = import_helper.get_all_components()
    for c in components:
        visited = set()
        if _has_cycle(import_helper, c, visited):
            pytest.fail(f"Cycle detected involving component {c}")
```

## Task 3: Tabelul de partajat vs. duplicat (real)

| Cod | Decizie | Motiv |
|---|---|---|
| **Session-token validation** | Share | 0 changes in 90 days, 3 consumatori: implementarea unică e criticală pentru securitate |
| **Answer-key parser** | Share | Copii stabile; copii divergente pot evalua același răspuns diferit |
| **Answer message schema** | Share ca contract, nu bibliotecă | Testing → Grading contract: versiuni, verificate cu contracte consumer-driven |
| **Roster CSV import** | Duplicate | 9 schimbări în 90 de zile la 2 consumatori: duplicarea e mai ieftină decât churn-ul partajării |
| **Report PDF formatting** | Duplicate | Cea mai volatilă celulă (23 schimbări): partajarea ar împinge churn-ul spre admin |

**Concluzie:** Volatilitatea decide: "Duplication is preferable to coupling" e un spectru, nu o regulă.

## Task 4: `verify-project2` formalizat ca ADL aserțiune

```
ASSERT(git diff --quiet -- pipelines apps)
ASSERT(contracts/ unchanged modulo artefact nume)
ASSERT(NEW project adds exactly ONE .sqlx in ../dataform/definitions/)
ASSERT(no hand-edit of spec/fixtures/ACCOUNT.src)
```
Acesta rulează ca fitness function la fiecare build, asigurând că noul proiect nu introduce atingeri în codul implementărilor existente.

---
*Generat în cadranul K03 — Frontiere între implementări în monorepo*