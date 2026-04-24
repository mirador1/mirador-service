# ADR-0059 — Grafana Cloud AI Observability (POC opt-in)

- **Status**: Accepted (POC)
- **Date**: 2026-04-24
- **Auteur(s)**: benoit.besson, Claude

## Contexte

Grafana Labs a annoncé à GrafanaCON 2026 (2026-04) un nouveau
produit **AI Observability** en public preview dans Grafana Cloud.
Il cible une lacune réelle : les outils d'observabilité classiques
(traces / logs / metrics) échouent à donner la visibilité qu'on
attend sur un LLM en production — conversations, tool calls,
token usage, coûts, qualité des réponses, policy violations.

**Ce que Grafana Cloud AI Observability apporte (d'après le
communiqué produit)** :
- Inspection conversation-by-conversation (prompt → completion)
- Correlations avec les signaux classiques (traces + logs +
  metrics) pour un end-to-end flow
- Continuous evaluation (détection low-quality, policy violations,
  data exposure)
- Intégration Grafana Assistant pour troubleshoot en NL
- OpenTelemetry support : instrument once via SDK léger →
  AI data capturée automatiquement
- Alerting LLM-based ou rule-based

Mirador a UN seul chemin AI en production : `BioService` qui appelle
Ollama via Spring AI 1.1.4 pour générer des bios customer (2-3
phrases) sur l'endpoint `GET /customers/{id}/bio`. Le chemin est
protégé par Resilience4j (bulkhead + circuit breaker).

**Spring AI 1.1+ émet des observations OTel** via Micrometer
automatiquement : `spring.ai.chat.client` span avec les attributs
`gen_ai.system`, `gen_ai.request.model`, `gen_ai.request.temperature`,
`gen_ai.response.usage.input_tokens`, `gen_ai.response.usage.output_tokens`,
latency, etc. Grafana Cloud AI Observability reconnaît ces attributs
+ construit automatiquement les dashboards AI correspondants.

Mirador dispose déjà d'une infra OTel Collector configurée en
**dual-export** (ADR-0054 : LGTM local + GitLab Observability).
Ajouter Grafana Cloud comme troisième destination est une
extension naturelle de cette architecture — pas une refonte.

## Décision

**POC opt-in** : ajouter Grafana Cloud AI Observability comme
**3e destination OTel traces**, activée via variables
d'environnement. Défaut OFF (aucun trafic réseau vers Grafana
Cloud). Activé → le dev bascule d'un dual-export à un
triple-export sans changement de code dans l'app.

### Ce qui est ajouté

1. **`infra/observability/otelcol-override.yaml`** : nouveau
   exporter `otlphttp/traces-grafana-cloud` avec endpoint + token
   injectés via `GRAFANA_CLOUD_OTLP_ENDPOINT` et `GRAFANA_CLOUD_OTLP_TOKEN`.
   Entrée dans le pipeline `traces.exporters` — le default token
   est un placeholder base64 qui 401 silencieusement si l'env var
   n'est pas surchargée (zero disruption si opt-out).

2. **`src/main/resources/application.yml`** : section
   `spring.ai.chat.client.observations` avec `log-prompt` +
   `log-completion` OPT-IN via `MIRADOR_AI_LOG_CONTENT` env var.
   Défaut **false** — les GenAI semantic conventions (model,
   tokens, latency) suffisent aux dashboards AI. Le content
   logging envoie le prompt + completion text, potentiellement
   sensible si l'utilisateur injecte du PII dans son nom /
   email. À n'activer que sur demos consented.

### Ce qui n'est PAS ajouté

- Pas de nouvelle dépendance Maven (Spring AI observations déjà
  built-in depuis 1.1)
- Pas de nouveau code dans `BioService` (instrumentation auto)
- Pas d'ADR-0054 amendé (dual-export reste dual ; triple est
  opt-in strict)

## Conséquences

### Acceptées

- **Triple-export overhead** : le collector local batch 3 fois
  avant export. Surcoût mémoire marginal (<50 MB pour notre
  volume). Latence app inchangée (export async).
- **Complexité config** : 3 env vars Grafana Cloud à gérer
  (endpoint + token + content flag). Documentées dans
  `docs/architecture/observability.md`.
- **Coût Grafana Cloud** : free tier couvre largement mirador
  (single AI endpoint, <100 reqs/mois typiquement en demo). Si
  le projet scale ou intègre un second AI path, revoir la tier.
- **Data privacy** : `log-prompt=true` envoie le contenu vers
  Grafana Cloud SaaS. Explicitement documenté comme dev-only ;
  prod governance doit approuver avant activation.
- **Vendor lock-in marginal** : les traces sont standard OTel,
  portables. On peut retirer Grafana Cloud à tout moment sans
  changement de code (juste retirer l'exporter du pipeline).

### Setup utilisateur (one-time)

1. Créer compte Grafana Cloud free : https://grafana.com/products/cloud/
2. Grafana Cloud UI → Connections → OpenTelemetry → Generate Auth Token
3. Le token fourni est paire `instanceId:token` déjà encodée
4. Export env vars :
   ```bash
   export GRAFANA_CLOUD_OTLP_ENDPOINT="https://otlp-gateway-prod-<region>.grafana.net/otlp"
   export GRAFANA_CLOUD_OTLP_TOKEN="<base64 from UI>"
   ./bin/run.sh restart
   ```
5. (Optionnel) `export MIRADOR_AI_LOG_CONTENT=true` pour
   envoyer aussi le contenu prompt/completion (demo uniquement).
6. Trigger un `POST /customers` + `GET /customers/{id}/bio` →
   traces visibles dans Grafana Cloud Explore avec les
   dashboards AI auto-générés.

## Alternatives envisagées

### A. Instrumentation manuelle OTel SDK dans BioService

Rejetée : Spring AI 1.1 observations suffisent (auto-émises
par Micrometer). Ajouter un span custom duplique.

### B. Switch complet LGTM local → Grafana Cloud

Rejetée : on perd le dev-loop offline + le `docker-compose up`
local. Dual/triple-export préserve les deux.

### C. Observabilité vendor-specific via Langfuse / OpenLLMetry

Rejetée : cassés l'homogénéité OTel standard. Les attributs
`gen_ai.*` sont canoniques dans la spec OpenTelemetry 2024+,
l'écosystème converge vers ça — Langfuse/OpenLLMetry sont des
détours propriétaires qui peuvent dériver.

### D. Pas de POC avant que le projet ait une vraie charge AI

Rejetée : l'infra OTel + Spring AI observations sont déjà là,
le coût d'ajout d'un exporter = 20 lignes YAML + 10 lignes
doc. À-faire-plus-tard devient à-faire-jamais si on n'active
pas le POC maintenant qu'on touche l'area.

## Retournements possibles

- Si Grafana Cloud AI Observability quitte la public preview en
  mode paywalled-only → évaluer migration vers LGTM local
  self-hosted avec les dashboards Grafana AI open-source (s'ils
  existent).
- Si le coût free-tier explose (jamais atteint en POC), retirer
  simplement l'exporter du pipeline `traces` dans
  `otelcol-override.yaml`. Zero autre changement.
- Si Spring AI change la forme des observations (unlikely avant
  2.0), mettre à jour les mappings Grafana Cloud côté UI plutôt
  que le code.

## Liens

- ADR-0054 — Dual-export GitLab Observability (pattern parent)
- ADR-0010 — OTLP push to collector (architecture d'origine)
- `infra/observability/otelcol-override.yaml` — config exporter
- `src/main/resources/application.yml` → `spring.ai.chat.client.observations`
- Product page (externe, check manuellement) :
  https://grafana.com/docs/grafana-cloud/monitor-applications/ai-observability/
