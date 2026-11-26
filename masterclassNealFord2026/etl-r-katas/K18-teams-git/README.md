# K18. Echipe și istoric Git

## Scenariu (5 min)
Dacă migrația ar fi un produs cu 3 echipe: cine deține `spec/` și `conformance/` (Platform), cine implementările (Stream-Aligned), cine Target System-ul; agentul care a scris 40% din commit-uri.

## Material sursă
- `CLAUDE.md` (reguli de siguranță, symlink-uri)
- `make-the-grade-pages-69-74/` (team alignment, describing teams, governing teams)
- `make-the-grade-pages-33-34/` (data topology tasks)
- `git log` real al repo-ului (commit-eri pe domenii specifice)
- Diagrame: `docs/diagrams/00-overview.svg`

## Task-uri (10 min, runde individual-discussion-team)

1. **Explică cu legea lui Conway + cognitive load de ce acum ("o singură mână") produce coerență**, și ce s-ar rupe la 3 echipe (drift între implementări).
2. **ADL echipe + persoane + DEFINIREA AGENT-ului ca membru**; Super User (arhitectul e "orice, mai ales urgențe") și Alumni.
3. **Gubernare pe istoric real:** aserțiuni tip findings (committeri în afara echipei, co-change `spec/` + `implementations/` în același commit – indicator boundary), cu dispoziții: conversație / problemă reală / schimbare de ADL.
4. **Măsoară eficiența agentului:** conflicte de merge + rata de livrare, lunar.