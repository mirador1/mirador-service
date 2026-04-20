# `terraform/aws/` — AWS reference implementation (ECS Fargate)

**Status: REFERENCE / STAGE 1** — never applied against a billing account.
Canonical target is GCP (see [`../gcp/`](../gcp/)). This module exists so
a reviewer or adopting team can see the project running on AWS without
rewriting from scratch.

See [ADR-0036](../../../docs/adr/0036-multi-cloud-terraform-posture.md)
for the decision.

## Why ECS Fargate, not EKS?

**EKS control-plane alone = $0.10/h = ~$72/month.** That's 7× the €10/month
project cap (ADR-0022) before a single pod runs. Ephemeral-cluster
pattern doesn't help — the fee is charged per cluster, per hour, regardless
of workload.

**ECS Fargate has no control-plane fee** — you pay only for the vCPU/memory
the tasks actually use. With the same sizing as the GCP module
(0.5 vCPU / 1 GB, single task, ~8h/month):

|                       | EKS 24/7 | ECS Fargate 24/7 | ECS Fargate 8h/month |
| --------------------- | -------- | ---------------- | -------------------- |
| Control plane         | $72/mo   | $0               | $0                   |
| Compute (1 task)      | ~$15/mo  | ~$18/mo          | ~€0.20/mo            |
| ALB                   | ~$16/mo  | ~$16/mo          | ~€0.20/mo            |
| **Total**             | **$103** | **$34**          | **€0.40**            |

EKS would be competitive only once you already pay for nodes (e.g.
3× t3.small always-on, ~$45/month) — then the control-plane fee becomes
a smaller percentage. Below that threshold Fargate wins.

We also lose Kubernetes-native features (CRDs, operators, Argo CD, ESO)
— which is the trade-off. The GCP module keeps those; the AWS module
does not. This is a deliberate stage-1 simplification.

## What gets provisioned

| Resource                                      | Purpose                                                           | Approx. cost (eu-west-3, always-on) |
| --------------------------------------------- | ----------------------------------------------------------------- | ----------------------------------- |
| `aws_ecs_cluster.main`                        | Logical ECS cluster (no control-plane fee)                        | €0                                  |
| `aws_cloudwatch_log_group.mirador`            | Task stdout/stderr, 7-day retention                               | ~€0.20/mo                           |
| `aws_iam_role.ecs_task_execution` + policy    | ECR pull + CloudWatch write                                        | €0                                  |
| `aws_security_group.alb` + `.tasks`           | ALB → task ingress (port 8080 only)                               | €0                                  |
| `aws_lb.main` (Application Load Balancer)     | Public HTTP entry point                                            | ~€16/mo fixed + LCU-hour            |
| `aws_lb_target_group.mirador`                 | Health-checked pool behind the ALB                                 | €0                                  |
| `aws_lb_listener.http`                        | Port 80 → target group                                             | €0                                  |
| `aws_ecs_task_definition.mirador`             | 0.5 vCPU / 1 GB Fargate task definition                           | per-task: ~€18/mo                   |
| `aws_ecs_service.mirador`                     | Keeps 1 task running, wires it to ALB                              | €0                                  |
| **Total always-on**                           |                                                                    | **~€34/mo**                         |
| **Total ephemeral (~8h/mo)**                  |                                                                    | **~€0.40/mo**                       |

## Files in this directory

| File                         | Role                                                                                         |
| ---------------------------- | -------------------------------------------------------------------------------------------- |
| `main.tf`                    | All resources above. Single-file module — small enough.                                      |
| `variables.tf`               | Inputs: `region`, `cluster_name`, `container_image` (required), `container_port`, `task_cpu`, `task_memory`, `app_host` (stage 2). |
| `outputs.tf`                 | `alb_dns_name`, `ecs_cluster_name`, `ecs_service_name`, `task_execution_role_arn`, `region`.   |
| `backend.tf`                 | Local backend only (stage 1). TODO in the file for S3 + DynamoDB migration.                  |
| `terraform.tfvars.example`   | Template for local apply.                                                                    |
| `README.md`                  | This file.                                                                                   |

## Prerequisites (one-time, per AWS account)

1. **Credentials** — `aws configure` or OIDC WIF from CI (not wired here yet).
2. **Container image published** — `docker push` to either:
   - Your own ECR: `aws ecr create-repository --repository-name mirador --region eu-west-3`
     then push to `<account>.dkr.ecr.eu-west-3.amazonaws.com/mirador:stable`.
   - GitLab Registry (public) — no AWS-side setup needed.
3. **Default VPC must exist** — some AWS org policies delete it.
   `aws ec2 describe-vpcs --filters Name=isDefault,Values=true` to check.

## Usage

```bash
brew install hashicorp/tap/terraform   # if not already installed

cd deploy/terraform/aws
cp terraform.tfvars.example terraform.tfvars   # edit, set container_image

terraform init      # local backend, no remote state bucket needed yet
terraform plan
terraform apply     # will prompt before making changes

# Get the URL
terraform output alb_dns_name
# → http://mirador-prod-alb-1234567890.eu-west-3.elb.amazonaws.com/
```

After provisioning the ALB takes ~60-90 seconds for DNS propagation before
`curl http://<alb-dns>/actuator/health` returns 200.

## Tear down

```bash
terraform destroy
```

ECS service + task definition go first, then ALB, then SGs + IAM + logs.
No deletion protection on any resource; destroy is unconditional.

## Known caveats (stage 1)

- **No HTTPS** — ALB listener is HTTP only. Stage 2 adds ACM cert +
  port-443 listener + HTTP → HTTPS redirect.
- **No persistent database** — the app expects Postgres; bring your own
  via environment variables on the task (stage 2 adds RDS or just
  passes `SPRING_DATASOURCE_URL` to an external DB).
- **No Redis / Kafka** — same pattern. ElastiCache + MSK are stage-2
  adds, or bring-your-own.
- **Plain-text environment variables** — `SPRING_PROFILES_ACTIVE` is
  hardcoded. Stage 2 moves secrets to AWS Secrets Manager via the
  `secrets` block in the task definition.
- **Single AZ for tasks** — `desired_count = 1` with default subnets.
  Multi-AZ + ≥2 replicas is stage 2.
- **No auto-scaling** — add `aws_appautoscaling_target` +
  `aws_appautoscaling_policy` when traffic exists.
- **Local Terraform state** — see `backend.tf` for the S3 migration path.

## What "stage 2" looks like

When this module graduates from reference to applied:

1. `backend.tf` → S3 backend + DynamoDB lock table.
2. RDS instance for Postgres (`db.t3.micro`, ~$15/month) + Parameter Store
   for the password.
3. Secrets Manager entries for JWT secret + any API keys.
4. ACM cert + Route53 record for `var.app_host`, HTTPS listener on 443.
5. WAF WebACL attached to the ALB (AWS Managed Rules, ~$5/month).
6. Auto-scaling policy on ECS service (target-tracking on CPU 60%).
7. VPC endpoints for ECR + CloudWatch + Secrets Manager (saves NAT
   traffic) once a custom VPC replaces the default.

## Related

- [`../gcp/`](../gcp/) — canonical target, full stack.
- [`../azure/`](../azure/) — AKS reference implementation (Azure parallel).
- [`../scaleway/`](../scaleway/) — Kapsule, EU-sovereign option.
- [ADR-0036](../../../docs/adr/0036-multi-cloud-terraform-posture.md) —
  multi-cloud Terraform posture.
- [ADR-0030](../../../docs/adr/0030-choose-gcp-as-the-kubernetes-target.md) —
  why GCP is the primary target (AWS EKS ruled out on cost alone).
- [ADR-0022](../../../docs/adr/0022-ephemeral-demo-cluster.md) — ephemeral
  cluster pattern that keeps the bill at ~€2/month.
