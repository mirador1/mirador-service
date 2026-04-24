# ADR-0046 — Numéro non utilisé (skip de numérotation)

- **Status**: Skip
- **Date**: 2026-04-24

## Contexte

Le numéro 0046 n'a jamais été assigné à une décision réelle. Lors
de la consolidation des ADRs 0041-0045 → 0041-0044 (commit `33e31e5`),
la séquence a sauté à 0047. Pour préserver la linéarité du
flat-index ADR (`docs/adr/README.md`) et respecter la règle
"ADR numbering gaps: fill or document the skip", ce stub documente
explicitement que 0046 est un **skip volontaire**, pas une décision
égarée.

## Décision

Ne PAS recycler le numéro 0046 pour une nouvelle ADR future. Les
nouvelles ADRs continuent à partir du dernier numéro utilisé
(actuellement 0057, voir `bin/dev/regen-adr-index.sh`).

## Justification

Recycler les numéros casse la traçabilité historique : un commit
référençant "ADR-0046" pourrait pointer vers une décision différente
selon la version du repo. Politique : **un numéro = une décision,
jamais réutilisé** même quand la décision est superseded ou skipped.

Cette règle s'applique à tous les ADRs (cf. ADR-0049 — CI shields
with dated exit tickets, qui suit la même logique de "trace plutôt
que recycle").
