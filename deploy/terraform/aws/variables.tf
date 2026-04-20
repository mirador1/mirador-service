# =============================================================================
# Terraform variables — AWS reference implementation
#
# Mirrors the GCP module's variables where possible, adapted to AWS
# concepts (account + region, ECR image URI, ECS task CPU/memory units).
#
# Set via terraform.tfvars (git-ignored) or TF_VAR_* env vars in CI.
# Related: main.tf (consumers), README.md (apply instructions).
# =============================================================================

# =============================================================================
# Role        : AWS region — binds every regional resource (VPC, ALB, ECS).
# Why         : Default `eu-west-3` (Paris) keeps the stack in the EU for
#               parity with the GCP module's europe-west1. Alternative
#               cheap EU regions: eu-west-1 (Ireland, typically cheapest),
#               eu-central-1 (Frankfurt). ECS/Fargate pricing is uniform
#               across EU regions (2026-04); difference is egress fees.
# Cost        : n/a (identifier). Pricing is regional but uniform across
#               major EU regions for Fargate.
# Gotchas     : AWS has no "global" region. IAM + Route53 are global but
#               still require a region set for the provider config.
# Related     : main.tf → provider "aws".
# =============================================================================
variable "region" {
  description = "AWS region (e.g. eu-west-3, eu-west-1, eu-central-1)"
  type        = string
  default     = "eu-west-3"
}

# =============================================================================
# Role        : ECS cluster name — also used as the prefix for every
#               other resource (ALB, target group, SGs, log group, role).
# Why         : Default matches the GCP `cluster_name = mirador-prod` for
#               consistency across clouds. Changing this variable recreates
#               every named resource — one-time decision per environment.
# Cost        : n/a (identifier)
# Gotchas     : ALB name limit is 32 chars; target group name is 32 chars.
#               The `substr(..., 0, 32)` wrappers in main.tf handle longer
#               cluster names, but output ends up truncated — keep the name
#               ≤ 28 chars to avoid visual surprises.
# Related     : main.tf (everywhere — name prefix).
# =============================================================================
variable "cluster_name" {
  description = "Name for the ECS cluster — used as prefix for all resources"
  type        = string
  default     = "mirador-prod"
}

# =============================================================================
# Role        : Container image URI — points at a pushed Docker image.
# Why         : No default — forcing an explicit value at plan time
#               prevents accidentally deploying a stale "latest" tag. For
#               ECR: `<account>.dkr.ecr.<region>.amazonaws.com/mirador:<tag>`.
#               For Docker Hub / GitLab Registry: full URI incl. host.
#               Image must be linux/amd64 (Fargate doesn't support arm64
#               in all regions as of 2026-04 — check per-region before
#               assuming).
# Cost        : n/a (string).
# Gotchas     : - If the image is private and NOT in ECR, the task needs
#                 `secrets` + a Secrets Manager entry with registry auth.
#                 Stage 2 adds this for Docker Hub pulls.
#               - An image on ECR in a different account requires
#                 `repositoryCredentials` even for Fargate. For cross-account
#                 sharing use ECR resource-based policies.
# Related     : main.tf → aws_ecs_task_definition.mirador.container_definitions.
# =============================================================================
variable "container_image" {
  description = "Docker image URI to run (e.g. 123456789012.dkr.ecr.eu-west-3.amazonaws.com/mirador:stable)"
  type        = string
}

# =============================================================================
# Role        : Port the Spring Boot app listens on inside the container.
# Why         : Default 8080 matches src/main/resources/application.yml
#               and deploy/kubernetes/backend/deployment.yaml. Changing
#               both is coupled — keep the default unless testing.
# Cost        : n/a (number).
# Gotchas     : The ALB listener is fixed to port 80 (public); this
#               variable is the *container* port. Mismatches between the
#               two surface as "target group shows unhealthy" after 30s.
# Related     : main.tf → aws_lb_target_group, aws_security_group.tasks.
# =============================================================================
variable "container_port" {
  description = "Port the container listens on"
  type        = number
  default     = 8080
}

# =============================================================================
# Role        : Fargate task CPU units — 1024 = 1 vCPU.
# Why         : Default 512 (0.5 vCPU) matches the K8s Deployment
#               resource requests. Smaller = cheaper; ECS rejects certain
#               combinations (e.g. 256 CPU + 2048 MB is invalid). Refer
#               to AWS "Task CPU and memory" matrix when tuning.
# Cost        : $0.04048 / vCPU / hour. 0.5 vCPU = $0.02024/h ≈ €14/month
#               if 24/7.
# Gotchas     : Valid CPU values: 256, 512, 1024, 2048, 4096, 8192,
#               16384. Any other number triggers a cryptic "Requested CPU
#               or Memory is invalid" error at `terraform apply`.
# Related     : https://docs.aws.amazon.com/AmazonECS/latest/developerguide/task-cpu-memory-error.html
# =============================================================================
variable "task_cpu" {
  description = "Fargate task CPU units (1024 = 1 vCPU; valid: 256, 512, 1024, 2048, 4096, 8192, 16384)"
  type        = number
  default     = 512
}

# =============================================================================
# Role        : Fargate task memory in MB.
# Why         : Default 1024 (1 GB) matches the Spring Boot app's JVM heap
#               + overhead. Going below 1 GB triggers OOMKilled under load
#               — measured during the GCP MR-64 shrinking pass.
# Cost        : $0.004445 / GB / hour. 1 GB = $0.004445/h ≈ €3/month if
#               24/7. Memory is comparatively cheap vs CPU; increasing
#               this is the first lever for "more headroom".
# Gotchas     : Must match a valid CPU:memory pair. With cpu = 512, valid
#               memory values are 1024-4096 MB.
# Related     : main.tf → aws_ecs_task_definition.mirador.memory.
# =============================================================================
variable "task_memory" {
  description = "Fargate task memory in MB (must pair with task_cpu per AWS matrix)"
  type        = number
  default     = 1024
}

# =============================================================================
# Role        : Public hostname — stage 2 (not consumed in main.tf yet).
# Why         : Mirrors var.app_host in the GCP module. Reserved for when
#               the ALB gets an ACM cert + Route53 alias record (stage 2).
#               Declared now so the tfvars.example has parity with GCP.
# Cost        : n/a (string). ACM certs for ALBs are free; Route53 hosted
#               zone = $0.50/month per zone.
# Gotchas     : The ALB's DNS name (`outputs.tf` → `alb_dns_name`) is the
#               stage-1 access point. `app_host` only starts mattering
#               once TLS is wired.
# Related     : variables.tf (GCP module) → var.app_host.
# =============================================================================
variable "app_host" {
  description = "Public hostname for the app (stage 2 — not consumed yet)"
  type        = string
  default     = ""
}
