locals {
  dns_enabled = var.dns_name != null
  # Hosted-zone domain: explicit override, else parent of dns_name (strip first label).
  zone_domain = var.dns_zone_domain != null ? var.dns_zone_domain : (
    local.dns_enabled ? join(".", slice(split(".", var.dns_name), 1, length(split(".", var.dns_name)))) : null
  )
}

# Security group governing traffic to the interface endpoint ENIs. Ingress is
# allowed from the VPC CIDR on 443: under EKS Auto Mode the pod ENIs use
# AWS-managed security groups that cannot be referenced as a stable resource, so
# a CIDR-based rule (mirroring the module's other interface endpoints) is used.
resource "aws_security_group" "endpoint" {
  name_prefix = "${var.name_prefix}-pl-${var.leg}-"
  description = "HTTPS from the migration VPC to the ${var.leg} PrivateLink endpoint"
  vpc_id      = var.vpc_id

  ingress {
    description = "HTTPS from the VPC"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = [var.vpc_cidr]
  }

  egress {
    description = "All outbound traffic"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-pl-${var.leg}"
  })
}

# Consumer interface endpoint to the provider's VPC endpoint service.
# private_dns_enabled is intentionally false: consumer-side private DNS only works
# when the provider published and verified a private DNS name on the service, and
# it cannot map an arbitrary FQDN. DNS is handled by the Route 53 zone below.
resource "aws_vpc_endpoint" "target" {
  vpc_id              = var.vpc_id
  service_name        = var.vpc_endpoint_service_name
  vpc_endpoint_type   = "Interface"
  private_dns_enabled = false
  subnet_ids          = var.subnet_ids
  security_group_ids  = [aws_security_group.endpoint.id]

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-pl-${var.leg}"
  })
}

# Optional private DNS so callers reach the target by its FQDN. Points the FQDN at
# the endpoint's regional DNS name. Requires the endpoint to be accepted and
# available; if the provider requires manual acceptance the record exists but does
# not resolve to a working endpoint until acceptance completes.
resource "aws_route53_zone" "target" {
  count = local.dns_enabled ? 1 : 0

  name    = local.zone_domain
  comment = "Private zone routing ${var.dns_name} to the ${var.leg} PrivateLink endpoint."

  vpc {
    vpc_id = var.vpc_id
  }

  tags = var.tags
}

resource "aws_route53_record" "target" {
  count = local.dns_enabled ? 1 : 0

  zone_id = aws_route53_zone.target[0].zone_id
  name    = var.dns_name
  type    = "CNAME"
  ttl     = 60
  records = [aws_vpc_endpoint.target.dns_entry[0].dns_name]
}
