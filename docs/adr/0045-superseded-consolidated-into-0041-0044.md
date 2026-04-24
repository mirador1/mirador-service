# ADR-0045 — Superseded (consolidated into ADR-0041..0044)

- **Status**: Superseded
- **Date origine**: 2026-04 (date inconnue, pré-consolidation)
- **Date supersedure**: 2026-04 (commit 33e31e5)

## Contexte

Cet ADR existait initialement comme `0045-pitest-stays-in-maven-site-not-sonarcloud.md`
mais a été consolidé avec ADR-0041..0044 dans le commit `33e31e5`
("consolidate ADRs 41-45 into 41-44 + supersede 0008").

Le contenu de l'ancien ADR-0045 (PIT mutation testing routing vers
Maven Site plutôt que SonarCloud) est désormais couvert par
ADR-0042 (Quality reports routing : SonarCloud vs Maven Site).

## Décision

Cet ADR est conservé comme **stub redirect** pour préserver la
linéarité du numéro et éviter le gap dans l'index — la règle
"ADR numbering gaps: fill or document the skip" est ainsi respectée
sans supprimer l'historique de la décision.

## Lien

- ADR-0042 — Quality reports routing : SonarCloud vs Maven Site
  (couvre maintenant le routing PIT)
- Commit `33e31e5` — consolidation
