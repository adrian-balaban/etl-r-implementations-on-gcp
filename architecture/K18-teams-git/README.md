# K18. Teams and Git history

## Scenario (5 min)
Suppose the migration were a product with 3 teams: who owns `spec/` and `conformance/` (Platform), who owns the implementations (Stream-Aligned), who owns the Target System; plus the agent that wrote 40% of the commits.

## Source material
- `CLAUDE.md` (the safety rules, the symlinks)
- `make-the-grade-pages-69-74/` (team alignment, describing teams, governing teams)
- `make-the-grade-pages-33-34/` (data topology tasks)
- the repo's real `git log` (committers on specific domains)
- Diagrams: `docs/diagrams/00-overview.svg`

## Tasks (10 min, individual-discussion-team rounds)

1. **Explain with Conway's Law + cognitive load why "one pair of hands" now produces coherence,** and what would break across 3 teams (drift between implementations).
2. **The team ADL: people + the AGENT defined as a member;** the Super User (the architect as "anything, especially emergencies") and Alumni.
3. **Governance on real history:** findings-style assertions (committers outside their team, `spec/` + `implementations/` co-changes in one commit – a boundary indicator), with dispositions: a conversation / a real problem / an ADL change.
4. **Measure the agent's effectiveness:** merge conflicts + delivery rate, monthly.