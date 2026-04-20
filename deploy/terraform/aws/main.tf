# =============================================================================
# Terraform — AWS infrastructure for mirador (reference implementation)
#
# Status: REFERENCE / STAGE 1 — not applied against a billing account.
# Canonical target is GCP (see deploy/terraform/gcp/). This module exists
# so a reviewer or adopting team can see the project running on AWS
# without rewriting from scratch.
#
# See ADR-0036 (multi-cloud Terraform posture) for the decision to keep
# GCP as the canonical target.
#
# Why ECS Fargate, not EKS?
# ─────────────────────────
# EKS control-plane = $0.10/h = ~$72/month flat, BEFORE any pod runs.
# That blows the €10/month project cap (ADR-0022) 7× on the cluster fee
# alone. ECS Fargate, in contrast, has no control-plane fee — you only
# pay for the vCPU/memory your tasks actually use:
#
#   Fargate pricing (eu-west-3, Paris, Apr 2026):
#     - vCPU:   $0.04048 / hour
#     - Memory: $0.004445 / GB-hour
#
#   1 task × 0.5 vCPU × 1 GB RAM = ~$0.025/hour ≈ €18/month if always up.
#
# For the demo's ephemeral pattern (~8h/month like GCP), ECS Fargate drops
# to ~€0.20/month — well inside budget. This is a deliberate trade-off:
# we lose Kubernetes-native features (CRDs, operators, ESO, Argo CD) in
# exchange for the €66/month EKS control-plane fee not existing.
#
# What this module provisions (minimal stage-1):
#   - Default VPC lookup (we reuse AWS's default VPC — same argument as
#     the GCP module, saves ~8 resources).
#   - ECS cluster (free — cluster abstraction, not a control plane).
#   - IAM task-execution role (for ECR pull + CloudWatch Logs).
#   - Fargate task definition for mirador-service.
#   - Application Load Balancer (ALB) + target group + listener on port 80.
#   - CloudWatch Logs group for task stdout/stderr.
#
# What's deferred (stage 2):
#   - RDS for PostgreSQL (bring-your-own via `var.postgres_url`).
#   - ElastiCache for Redis (bring-your-own via `var.redis_url`).
#   - MSK / Kafka — Pub/Sub equivalent not decided (AWS MSK Serverless is
#     the natural fit but doubles the bill).
#   - WAF, ACM cert on the ALB — stage 2 when a real hostname is wired.
#   - Auto-scaling policy — stage 2 when traffic exists to scale against.
#
# Related:
#   - variables.tf            — inputs (region, app_host, image, etc.)
#   - outputs.tf              — ALB DNS + cluster name
#   - backend.tf              — local state (TODO: migrate to S3 + DynamoDB)
#   - README.md               — apply instructions + cost breakdown
#   - ADR-0036                — multi-cloud posture decision
#   - deploy/terraform/gcp/   — the canonical target for comparison
# =============================================================================

# =============================================================================
# Role        : Terraform core requirements — pin language + provider versions.
# Why         : Same rationale as GCP module — lock the toolchain so CI and
#               local runs produce identical plans. The AWS provider 5.x
#               series is the current stable major; 6.x is not yet GA as
#               of 2026-04.
# Cost        : n/a (metadata only)
# Gotchas     : The AWS provider frequently bumps minimum Terraform core
#               versions in minor releases. Keep required_version ≥ 1.8 to
#               match the GCP module and the CI image.
# Related     : deploy/terraform/gcp/main.tf (identical constraint).
# =============================================================================
terraform {
  required_version = ">= 1.8"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

# =============================================================================
# Role        : AWS provider — binds every resource to one region.
# Why         : AWS is strictly regional (unlike GCP where provider has a
#               region but several resources are global). Forcing a region
#               here means an accidental deploy to us-east-1 is impossible.
#               Default var.region = eu-west-3 (Paris) keeps the stack in
#               the EU for data-sovereignty parity with the GCP module's
#               europe-west1.
# Cost        : n/a (metadata only)
# Gotchas     : - Some AWS resources (IAM, Route53, CloudFront) are global
#                 and ignore this region. Terraform still expects a region
#                 for their API calls, hence keeping this configured.
#               - `AWS_PROFILE` or `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY`
#                 env vars override any `profile` attribute we might add
#                 — useful in CI where assume-role via OIDC produces env
#                 credentials.
# Related     : variables.tf → var.region.
# =============================================================================
provider "aws" {
  region = var.region
}

# =============================================================================
# Role        : Look up the default VPC + its subnets — zero-cost network
#               substrate for the ALB + Fargate tasks.
# Why         : Same simplification as the GCP module (using `default` VPC).
#               A production deployment would define a dedicated VPC with
#               private subnets + NAT gateway, but that's ~8 resources and
#               ~$35/month just for the NAT gateway — disproportionate for
#               a reference implementation.
# Cost        : €0 (VPC and subnets are free; NAT gateway would be the
#               expensive add).
# Gotchas     : - If the AWS account has no default VPC (some organisations
#                 delete it), this data source errors. Document the
#                 workaround (create a custom VPC) in README.md.
#               - `default_for_az = true` returns the default subnet per
#                 AZ. If the account has ≥3 AZs, we get ≥3 public subnets
#                 — enough for ALB multi-AZ placement.
# Related     : aws_lb.main (subnets argument).
# =============================================================================
data "aws_vpc" "default" {
  default = true
}

data "aws_subnets" "default" {
  filter {
    name   = "vpc-id"
    values = [data.aws_vpc.default.id]
  }
  filter {
    name   = "default-for-az"
    values = ["true"]
  }
}

# =============================================================================
# Role        : ECS cluster — logical grouping for Fargate tasks.
# Why         : ECS clusters are free (no control-plane fee, unlike EKS).
#               We create one per environment so CloudWatch metrics and
#               IAM scoping stay tidy. Naming matches var.cluster_name,
#               default `mirador-prod`, for symmetry with the GCP module.
# Cost        : €0 for the cluster itself. You pay only for the Fargate
#               tasks running inside it.
# Gotchas     : - An ECS cluster cannot be deleted while any tasks or
#                 services still reference it. `terraform destroy` handles
#                 this in the correct order automatically.
#               - Container Insights (detailed CloudWatch metrics) is off
#                 by default. Enabling it costs ~$1.50/cluster/month and
#                 adds per-metric charges.
# Related     : aws_ecs_service.mirador, aws_ecs_task_definition.mirador.
# =============================================================================
resource "aws_ecs_cluster" "main" {
  name = var.cluster_name

  setting {
    name  = "containerInsights"
    value = "disabled"
  }

  tags = {
    Project = "mirador"
    Env     = "reference"
    Managed = "terraform"
  }
}

# =============================================================================
# Role        : CloudWatch Log group for ECS task stdout/stderr.
# Why         : Fargate tasks can only ship logs to CloudWatch (or stream
#               over FireLens — stage 2). Without a log group the task
#               fails to start. Retention set to 7 days to cap the bill;
#               production would bump to 30-90 days.
# Cost        : $0.50 per GB ingested + $0.03 per GB-month storage. 7-day
#               retention on a demo with ~100MB/day logs = ~€0.20/month.
# Gotchas     : - Log group name must match the task definition's
#                 `options.awslogs-group`. Typo here = silently missing
#                 logs, task "works" but no visibility.
#               - Encryption at rest (kms_key_id) is not set — stage 2
#                 adds a customer-managed KMS key.
# Related     : aws_ecs_task_definition.mirador.container_definitions.
# =============================================================================
resource "aws_cloudwatch_log_group" "mirador" {
  name              = "/ecs/${var.cluster_name}"
  retention_in_days = 7

  tags = {
    Project = "mirador"
  }
}

# =============================================================================
# Role        : IAM role that Fargate assumes to pull images + write logs.
# Why         : ECS needs two role types — task-execution (for the ECS
#               agent: pull from ECR, write to CloudWatch Logs, fetch
#               Secrets Manager values) and task role (for the app itself:
#               S3, DynamoDB, whatever AWS APIs it needs). This resource
#               is the former; the task role is omitted because the
#               reference app doesn't call AWS APIs.
# Cost        : €0 (IAM roles are free).
# Gotchas     : - AssumeRolePolicy must grant `ecs-tasks.amazonaws.com`,
#                 NOT `ecs.amazonaws.com` — the latter is the ECS service
#                 itself and results in a cryptic "unable to assume role"
#                 error at task start.
#               - The AmazonECSTaskExecutionRolePolicy managed policy
#                 covers ECR pull + CloudWatch logs only. Adding Secrets
#                 Manager access needs an inline policy (stage 2).
# Related     : aws_ecs_task_definition.mirador.execution_role_arn.
# =============================================================================
resource "aws_iam_role" "ecs_task_execution" {
  name = "${var.cluster_name}-task-execution"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Principal = {
        Service = "ecs-tasks.amazonaws.com"
      }
      Action = "sts:AssumeRole"
    }]
  })

  tags = {
    Project = "mirador"
  }
}

resource "aws_iam_role_policy_attachment" "ecs_task_execution" {
  role       = aws_iam_role.ecs_task_execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

# =============================================================================
# Role        : Security group allowing inbound HTTP from the ALB to tasks,
#               and unrestricted egress (for image pull + calls to any
#               bring-your-own managed DB/cache).
# Why         : Fargate tasks run in VPC-native ENIs and need a security
#               group just like EC2 instances. Narrow ingress to the ALB
#               SG (not 0.0.0.0/0) so tasks aren't directly reachable from
#               the internet — enforces the ALB-as-only-entry-point model.
# Cost        : €0.
# Gotchas     : - Egress 0.0.0.0/0 is wide. Stage 2 tightens this to
#                 VPC endpoints for ECR + CloudWatch + Secrets Manager
#                 (NAT-free, cheaper, more secure).
#               - Self-reference between task SG and ALB SG requires two
#                 resources; cycle detection in TF handles this OK.
# Related     : aws_lb.main.security_groups.
# =============================================================================
resource "aws_security_group" "alb" {
  name        = "${var.cluster_name}-alb"
  description = "ALB — accepts HTTP from internet"
  vpc_id      = data.aws_vpc.default.id

  ingress {
    description = "HTTP from anywhere"
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    description = "All outbound"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = { Project = "mirador" }
}

resource "aws_security_group" "tasks" {
  name        = "${var.cluster_name}-tasks"
  description = "ECS tasks — accept from ALB, egress everywhere"
  vpc_id      = data.aws_vpc.default.id

  ingress {
    description     = "From ALB only"
    from_port       = var.container_port
    to_port         = var.container_port
    protocol        = "tcp"
    security_groups = [aws_security_group.alb.id]
  }

  egress {
    description = "All outbound"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = { Project = "mirador" }
}

# =============================================================================
# Role        : Application Load Balancer — public entry point.
# Why         : ALB is the AWS equivalent of the GCP Ingress + L7 LB. It
#               terminates HTTP on port 80, distributes across AZs, and
#               performs health checks on `/actuator/health` before routing
#               traffic to a task.
#               Internal = false → public ALB. Stage 2 adds an ACM cert
#               + HTTPS listener on 443 with HTTP → HTTPS redirect.
# Cost        : ~$16/month fixed + $0.008 per LCU-hour based on traffic.
#               For the demo's near-zero traffic: ~€16/month if left up
#               24/7. The ephemeral pattern (destroy after demo) brings
#               this to single-digit euros.
# Gotchas     : - ALBs need ≥2 subnets in distinct AZs. The `default_for_az`
#                 filter above normally returns 3 subnets in eu-west-3.
#               - `deletion_protection = false` matches the GCP cluster's
#                 deletion_protection = false — ephemeral pattern needs
#                 `terraform destroy` to work unconditionally.
#               - Idle timeout defaults to 60s. Spring Boot async handlers
#                 may need higher; tune in stage 2 if they appear.
# Related     : aws_lb_target_group.mirador, aws_lb_listener.http.
# =============================================================================
resource "aws_lb" "main" {
  name               = substr("${var.cluster_name}-alb", 0, 32)
  internal           = false
  load_balancer_type = "application"
  security_groups    = [aws_security_group.alb.id]
  subnets            = data.aws_subnets.default.ids

  enable_deletion_protection = false

  tags = { Project = "mirador" }
}

resource "aws_lb_target_group" "mirador" {
  name        = substr("${var.cluster_name}-tg", 0, 32)
  port        = var.container_port
  protocol    = "HTTP"
  target_type = "ip" # Fargate requires target_type = "ip"
  vpc_id      = data.aws_vpc.default.id

  health_check {
    enabled             = true
    path                = "/actuator/health/readiness"
    matcher             = "200"
    interval            = 30
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }

  tags = { Project = "mirador" }
}

resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.main.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.mirador.arn
  }
}

# =============================================================================
# Role        : Fargate task definition — declares container image, CPU,
#               memory, port, environment, and log config.
# Why         : Mirrors the GCP Deployment manifest (deploy/kubernetes/
#               backend/deployment.yaml) at minimum viable config. The app
#               is single-container; a sidecar pattern (e.g. X-Ray daemon
#               or CloudWatch agent) is stage 2.
#
#               Sizing 0.5 vCPU / 1 GB memory matches the Kubernetes
#               resource requests of the GKE deployment — same cost target
#               ~€18/month if 24/7.
# Cost        : $0.04048/vCPU-hour + $0.004445/GB-hour
#               = 0.5 × 0.04048 + 1 × 0.004445 = $0.024685/hour
#               ≈ €18/month if 1 task always running.
# Gotchas     : - Fargate CPU + memory combinations are restricted (not
#                 arbitrary). 512 CPU units (0.5 vCPU) + 1024 MB is valid;
#                 see AWS docs "Task CPU and memory" for the matrix.
#               - `requires_compatibilities = ["FARGATE"]` is non-optional;
#                 omitting it falls back to EC2 launch mode which this
#                 module isn't wired for.
#               - Environment variables are plain-text here. Stage 2 moves
#                 DB password / JWT secret to Secrets Manager via the
#                 `secrets` block in container_definitions.
# Related     : aws_ecs_service.mirador (what actually runs this task).
# =============================================================================
resource "aws_ecs_task_definition" "mirador" {
  family                   = var.cluster_name
  network_mode             = "awsvpc"
  requires_compatibilities = ["FARGATE"]
  cpu                      = var.task_cpu
  memory                   = var.task_memory
  execution_role_arn       = aws_iam_role.ecs_task_execution.arn

  container_definitions = jsonencode([{
    name      = "mirador"
    image     = var.container_image
    essential = true
    portMappings = [{
      containerPort = var.container_port
      protocol      = "tcp"
    }]
    environment = [
      { name = "SPRING_PROFILES_ACTIVE", value = "prod" },
      # Stage-2: push DB / Redis / Kafka connection env vars here, OR
      # use `secrets` block to pull from AWS Secrets Manager.
    ]
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.mirador.name
        awslogs-region        = var.region
        awslogs-stream-prefix = "ecs"
      }
    }
  }])

  tags = { Project = "mirador" }
}

# =============================================================================
# Role        : ECS Service — keeps N tasks running, wires them to the ALB
#               target group, handles rolling updates.
# Why         : A task definition alone doesn't run anything; an ECS
#               Service is the long-lived supervisor. desired_count = 1
#               matches the single-replica demo policy (ADR-0014).
# Cost        : €0 for the service itself — you only pay for the tasks.
# Gotchas     : - health_check_grace_period_seconds defaults to 0; a cold
#                 JVM start takes ~45s on Fargate, so giving 90s prevents
#                 premature "unhealthy" + restart loop.
#               - Deployment circuit breaker is off by default. Turning it
#                 on would automatically roll back a failed deployment;
#                 recommended for anything beyond stage 1.
# Related     : aws_lb_target_group.mirador, aws_ecs_task_definition.mirador.
# =============================================================================
resource "aws_ecs_service" "mirador" {
  name            = var.cluster_name
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.mirador.arn
  desired_count   = 1
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = data.aws_subnets.default.ids
    security_groups  = [aws_security_group.tasks.id]
    assign_public_ip = true # Default subnets lack NAT; Fargate needs public IP to pull ECR
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.mirador.arn
    container_name   = "mirador"
    container_port   = var.container_port
  }

  health_check_grace_period_seconds = 90

  depends_on = [aws_lb_listener.http]

  tags = { Project = "mirador" }
}
