# Răspunsuri K14: Cuantificarea capabilităților

## Task 1: Capability ADL

**Simplicity** (# resurse Terraform, complexitate, cost):
- **Flink:** 71 resurse (best), CC mig 4.6 (prajit în zona Orange dacă >5), cost $0.90 (best)
- **Flink-jdbc:** 81 resurse, CC 4.6, cost $0.94
- **Beam Dataflow:** 75 resurse, cost $2.33 (worst ~2.5× Flink)

**Deployability** (ramp-up + timp de run):
- **Flink:** ramp-up 23 min 36 s (worst din cauza Kafka), run 6 min 55 s (best cu paralelism 1)
- **Flink-jdbc:** ramp-up 14 min 25 s (best), run 5 min 59 s (best)
- **Beam Dataflow:** ramp-up 50 min 56 s (worst, Composer + Dataform), run 38 min 25 s

**Scalability** (200k users, start-up dominant):
- **Flink:** 3 job-uri, paralelism 1, pe un singur nod GKE 4-vCPU. Start-up dominate la 200k.
- **Flink-jdbc:** asemenea GKE, dar load 200k rows în sursa Postgres se întâmplă înainte de run (4 min 20 s).
- **Beam Dataflow:** 3 job-uri Dataflow, un worker 1 vCPU fiecare, plus Dataform. Run 38 min 25 s, dar include o retry (fără retry ~28 min).

**Cost:**
- **Flink:** $0.90 (resource count 71)
- **Flink-jdbc:** $0.94 (resource count 81, +$0.04)
- **Beam Dataflow:** $2.33 (resource count 75, dar Composer $1.03 fixat)

**Concluzie pe capacități:**
- **Simplicity:** Flink câștigă (mai puține resurse, cost minim)
- **Deployability:** Flink-jdbc câștigă (ramp-up cel mai scurt, run cel mai rapid)
- **Scalability:** Flink și Flink-jdbc sunt echivalenți la start-up; Beam e mai lent din cauza overhead-ului Composer.
- **Cost:** Flink e câștigătorul absolut ($0.90 vs $0.94 vs $2.33).

## Task 2: Ce spun datele despre fit-ul fiecărei implementări?

**Tendințe (din evidence pack-uri, exact 1 run fiecare):**
- **Flink:** Cost mic, timp de run mediu, resurse optimum, dar Kafka poate varia ramp-up cu ~10 min (cloud variance).
- **Flink-jdbc:** Cost foarte similar cu Flink, timp de run cel mai rapid, resurse în mod ușurat peste medie, dar necesită 2 instanțe Cloud SQL (source + pipeline).
- **Beam Dataflow:** Costul cel mai mare (~2.5× Flink), tot timpul de run și ramp-up cel mai lent, dar izolarea celor mai bună (fault tolerance ★★★★★) și nu are Kafka-varianța la fel de severă.

**Ce e trend vs. un singur run (Limits!):**
- Datele sunt pentru o singură rulă pe implementare, nu o serie statistică.
- Checklist-ul §8 al cursului subliniază: "o singură măsuraretoare nu e dovadă de readiness".
- Pentru a spune că un architecture e "gata", trebuie să verificăm trendurile lunare/lunare, nu doar un run.

**Metrice temporale lipsă în evidence:**
- Nu avem metrice de "time-to-first-byte" pentru fiecare etapă separată (doar totalul).
- Nu avem metrice de "CPU utilization" pe timpul run-ului.
- Nu avem metrice de "memory pressure" sau "garbage collection pauses".
- Lisă: connection waits per database pe hver run (doar la Flink-jdbc avem source + pipeline PostgreSQL).

## Task 3: ADL composite: Maintainability

```
DEFINE CONST MAX_CC AS 10
DEFINE CONST MAX_DISTANCE AS 0.3
DEFINE CAPABILITY Maintainability AS {
    average cyclomatic complexity of cls < MAX_CC,
    normalized distance from the main sequence for comp < MAX_DISTANCE,
    resources < 100
}
FOREACH component : comp IN system
    FOREACH class : cls IN comp
        ASSERT(cyclomatic complexity of m < MAX_CC FOR method m)
    END
    ASSERT(normalized distance from the main sequence for comp < MAX_DISTANCE)
ENDFOREACH
ASSERT(resources per component < 100)
```

**Interpretarea:**
- **Zone of Pain (D > 0.3):** Dacă D > 0.3, componenta e în zona de "pain" – orice schimbare e costantă și probabilă să provoce defections.
- **Zone of Uselessness (A > 0.6, I > 0.75):** Dacă componenta e predońferend pe abstracte, este inutilă – nu behavior, doar architectură.
- **Ideala:** D < 0.3, A între 0.2-0.6, I < 0.5 – componenta e pe main sequence, ușor de menținut și extins.

## Task 4: Aplicare pe ETL-R

Pentru a evalua fiecărei implementări pe capacități:
1. **Flink:** Poate trece testele de maintainability dacă refactorizăm `grade()` în interfețe (ca în task-urile derefacere K04). Costul e minim, dar ramp-up-ul Kafka e riscul principal.
2. **Flink-jdbc:** Poate trece testele cu facilități optimale – CC-ul e similar cu Flink, dar nu are varianța Kafka. Costul e minim (+$0.04). Recommended pentru noi proiecte care au bază de date sursă Postgres.
3. **Beam Dataflow:** Are provocări serioase pentru maintainability datorită overhead-ului Composer și Dataform. Costul ridicat ($2.33) face că nu e recomandat pentru termenii de 6 luni, deși e cel mai robust din cauza fault tolerance-ului.

**Recomandare:** Pentru noi proiecte cu deadline 6 luni și 200k students, **Flink-jdbc** dacă avem bază de date Postgres, sau **Flink** dacă lucram cu fișiere TDS. Beam e justificat doar pentru workload-uri mici sau when cost-ul nu e factorul principal.

---
*Generat în cadranul K14 – Cuantificarea capabilităților*