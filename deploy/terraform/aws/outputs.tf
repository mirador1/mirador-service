# =============================================================================
# Outputs — consumed by CI scripts and humans running `terraform output`.
#
# Mirrors the GCP module's outputs where concepts map cleanly.
# =============================================================================

# =============================================================================
# Role        : ALB DNS name — the stage-1 access URL.
# Why         : With no Route53 wiring yet, the ALB's auto-generated DNS
#               name (something like
#               `mirador-prod-alb-1234567890.eu-west-3.elb.amazonaws.com`)
#               IS the URL. Output it so scripts and humans can reach
#               the deployed app without hunting through the console.
# Cost        : n/a (string).
# Gotchas     : DNS name only resolves after the ALB finishes provisioning
#               — typically 60-90 seconds after `apply` completes.
# Related     : main.tf → aws_lb.main.
# =============================================================================
output "alb_dns_name" {
  description = "ALB DNS name — the stage-1 access URL (http://<this>/)"
  value       = aws_lb.main.dns_name
}

# =============================================================================
# Role        : ECS cluster name, for scripts that need it verbatim.
# Why         : Parity with the GCP module's `gke_cluster_name`. Used by
#               `aws ecs update-service --cluster ...` commands in CI to
#               trigger a rolling task refresh after pushing a new image.
# Cost        : n/a (string).
# Gotchas     : ECS clusters are region-scoped — the cluster name alone
#               is not globally unique. CI commands must also pass
#               `--region`.
# Related     : main.tf → aws_ecs_cluster.main.
# =============================================================================
output "ecs_cluster_name" {
  description = "ECS cluster name — use with: aws ecs update-service --cluster"
  value       = aws_ecs_cluster.main.name
}

# =============================================================================
# Role        : ECS service name.
# Why         : Needed alongside cluster name for `aws ecs update-service`.
# Cost        : n/a (string).
# Gotchas     : n/a.
# Related     : main.tf → aws_ecs_service.mirador.
# =============================================================================
output "ecs_service_name" {
  description = "ECS service name"
  value       = aws_ecs_service.mirador.name
}

# =============================================================================
# Role        : IAM role ARN used by tasks to pull from ECR + log.
# Why         : Stage-2 teams adding custom inline policies (Secrets
#               Manager, S3 upload, etc.) need this ARN to attach the
#               policies programmatically.
# Cost        : n/a (string).
# Related     : main.tf → aws_iam_role.ecs_task_execution.
# =============================================================================
output "task_execution_role_arn" {
  description = "IAM role ARN for ECS task execution (ECR pull + CloudWatch logs)"
  value       = aws_iam_role.ecs_task_execution.arn
}

# =============================================================================
# Role        : Region echo — for scripts that need it without re-reading tfvars.
# Why         : Lets `terraform output -json | jq -r .region.value` feed
#               `aws --region` calls in CI without a duplicated source.
# Cost        : n/a (string).
# Related     : variables.tf → var.region.
# =============================================================================
output "region" {
  description = "AWS region where the stack is deployed"
  value       = var.region
}
