# Customer Churn prediction — feature documentation

> **Status** : Phase B (Java inference) shipped ; Python inference (Phase
> C), UI page (Phase D), drift SLO (Phase E), promotion script (Phase F)
> in progress.

The `mirador-service-java` backend exposes a trained Customer Churn
prediction via two interfaces — a REST endpoint and an MCP `@Tool`. The
underlying model is an [ONNX-format binary](https://onnxruntime.ai/) loaded
in-process at startup ; same ML lifecycle as the
[shared ADR-0060](https://gitlab.com/mirador1/mirador-service-shared/-/blob/main/docs/adr/0060-onnx-cross-language-ml-inference.md)
contract — no sidecar, no network hop per inference, identical
predictions across Java + Python.

## Architecture (Phase B summary)

```
┌─ HTTP / MCP request : customer_id ─────────────────────────────────┐
│                                                                     │
│  ChurnController (REST)              ChurnMcpToolService (MCP)      │
│         │                                       │                   │
│         │  loads :                              │                   │
│         │   • Customer (CustomerRepository)     │                   │
│         │   • Orders (OrderRepository)          │                   │
│         │   • OrderLines (OrderLineRepository)  │                   │
│         ▼                                       ▼                   │
│             ChurnFeatureExtractor                                   │
│         (8-feature float[] vector — see ADR-0061)                   │
│                       │                                             │
│                       ▼                                             │
│            ChurnPredictor (ONNX Runtime in-process)                 │
│                  /etc/models/churn_predictor.onnx                   │
│                       │                                             │
│                       ▼ raw logit                                   │
│            sigmoid(logit) → probability ∈ [0, 1]                    │
│                       │                                             │
│                       ▼                                             │
│             ChurnPredictionDto                                      │
│       (probability, riskBand, topFeatures, modelVersion, predictedAt)│
└─────────────────────────────────────────────────────────────────────┘
```

## REST endpoint

```
POST /customers/{id}/churn-prediction
Authorization: Bearer <JWT>     OR     X-API-Key: <key>
```

**Responses** :
- `200` + `ChurnPredictionDto` JSON
- `404` if the customer doesn't exist
- `503` if the ONNX model isn't loaded yet (file missing under the
  configured path — see [Model provisioning](#model-provisioning))
- `500` if the runtime fails (rare ; usually NaN inputs)

**Example** :
```bash
TOKEN=$(curl -s -X POST localhost:8080/auth/login \
    -H 'Content-Type: application/json' \
    -d '{"username":"admin","password":"admin"}' | jq -r .accessToken)

curl -s -X POST -H "Authorization: Bearer $TOKEN" \
    localhost:8080/customers/42/churn-prediction | jq
```

```json
{
  "customerId": 42,
  "probability": 0.731,
  "riskBand": "HIGH",
  "topFeatures": ["days_since_last_order", "total_revenue_90d", "order_frequency"],
  "modelVersion": "v3-2026-04-27",
  "predictedAt": "2026-04-27T15:42:18.392Z"
}
```

## MCP tool

```
predict_customer_churn(customer_id: Long) → ChurnPredictionDto | NotFoundDto | ServiceUnavailableDto
```

**Same logic, MCP-compatible soft-error DTOs** instead of HTTP status
codes. The LLM caller receives a parseable JSON shape that lets it
reason about retry / fallback rather than getting an exception.

```bash
# Wire it up in claude.
claude mcp add --transport http mirador-java http://localhost:8080/mcp \
    --header "X-API-Key: demo-api-key-2026"

# Ask in natural language :
claude
> Predict the churn risk for customer 42. Show me the probability and the band.
```

## Model provisioning

Per [shared ADR-0062](https://gitlab.com/mirador1/mirador-service-shared/-/blob/main/docs/adr/0062-mlflow-registry-configmap-promotion.md),
the ONNX artefact is distributed via a Kubernetes ConfigMap. Pods mount
it read-only at `/etc/models/churn_predictor.onnx`.

**Configuration properties** :
- `mirador.churn.model-path` (default `/etc/models/churn_predictor.onnx`)
- `mirador.churn.model-version` (default `unspecified`) — surfaced in
  every `ChurnPredictionDto` for audit + drift correlation
- `mirador.churn.risk-thresholds` (default LOW=0.3, HIGH=0.7) — band
  classification thresholds (Phase E will move these to a tunable
  config-server endpoint)

**Local dev** : the Python training script
[`mirador_service.ml.train_churn`](https://gitlab.com/mirador1/mirador-service-python/-/blob/main/src/mirador_service/ml/train_churn.py)
writes to `./.models/churn_predictor.onnx` by default. Override via :
```bash
./mvnw spring-boot:run -Dmirador.churn.model-path=$(pwd)/../mirador-service-python/.models/churn_predictor.onnx
```

**Production promotion** is `bin/ml/promote_to_configmap.sh` (Phase F)
— pulls the latest `Production`-tagged ONNX from MLflow, generates the
ConfigMap YAML, and triggers a rolling restart.

## Graceful degradation

The model is **not strictly required for the application to boot**. If
the ONNX file is missing :

- `ChurnPredictor#isReady()` returns `false`.
- REST endpoint returns `503`.
- MCP tool returns `ServiceUnavailableDto` with a clear hint.
- All other Mirador endpoints (Customer / Order / Product / MCP /
  Actuator / SLO dashboards) keep working unchanged.

This pattern lets us deploy the JAR before the model is ready (e.g. on a
fresh cluster where the ConfigMap hasn't been provisioned yet) without
gating the whole stack on the ML promotion.

## Cross-language guarantee

Per shared ADR-0060 §"Verification protocol", the same `.onnx` file must
yield identical predictions in Java + Python (≤ 1e-6 floating-point
tolerance). Phase G ships `bin/ml/cross_language_smoke.py` that validates
this on every promoted model.

The 8 features + their canonical order (per [shared
ADR-0061](https://gitlab.com/mirador1/mirador-service-shared/-/blob/main/docs/adr/0061-customer-churn-prediction.md)
§"Feature engineering") are the contract — both
[`ChurnFeatureExtractor`](../../src/main/java/com/mirador/ml/ChurnFeatureExtractor.java)
(Java) and
[`feature_engineering.py`](https://gitlab.com/mirador1/mirador-service-python/-/blob/main/src/mirador_service/ml/feature_engineering.py)
(Python) implement the same logic. Tests on both sides assert determinism
on golden inputs.

## Testing

Unit tests under `src/test/java/com/mirador/ml/` :
- `ChurnFeatureExtractorTest` — feature engineering parity (the load-bearing test)
- `ChurnPredictorTest` — graceful degradation (model missing, wrong feature shape)
- `ChurnMcpToolServiceTest` — MCP soft-error DTOs
- `RiskBandTest` — boundary classification

Integration test in `McpServerITest` — verifies the
`predict_customer_churn` tool is registered alongside the other 14
MCP tools.

## What's next (Phase C → F)

| Phase | Repo | Scope |
|---|---|---|
| **C** | mirador-service-python | Python in-process inference (mirror Java via `onnxruntime` + same ONNX file) |
| **D** | mirador-ui | `/insights/churn` page : top-10 at-risk customers, search-by-id, drift over 30 days |
| **E** | mirador-service-shared | MLflow tracking + drift SLO + dashboard + runbook |
| **F** | mirador-service-shared | `bin/ml/promote_to_configmap.sh` + K8s deployment volumeMount + Argo CD GitOps |

## References

- [shared ADR-0060 — ONNX cross-language inference](https://gitlab.com/mirador1/mirador-service-shared/-/blob/main/docs/adr/0060-onnx-cross-language-ml-inference.md)
- [shared ADR-0061 — Customer Churn pipeline](https://gitlab.com/mirador1/mirador-service-shared/-/blob/main/docs/adr/0061-customer-churn-prediction.md)
- [shared ADR-0062 — MLflow registry + ConfigMap promotion](https://gitlab.com/mirador1/mirador-service-shared/-/blob/main/docs/adr/0062-mlflow-registry-configmap-promotion.md)
- [Microsoft ONNX Runtime Java API](https://onnxruntime.ai/docs/get-started/with-java.html)
- [Python training pipeline](https://gitlab.com/mirador1/mirador-service-python/-/blob/main/src/mirador_service/ml/train_churn.py)
