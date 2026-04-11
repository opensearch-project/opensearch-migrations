# Global ARGs (must be before first FROM to be usable in FROM lines)
ARG BUILDER_IMAGE=docker.io/library/golang:1.24-alpine
ARG RUNTIME_IMAGE=docker.io/library/amazonlinux:2023

# --- Stage 1: Build Go binary ---
FROM ${BUILDER_IMAGE} AS go-builder
ARG TARGETARCH

WORKDIR /build
RUN apk add --no-cache ca-certificates

COPY opensearch-pricing-calculator/go.mod opensearch-pricing-calculator/go.sum ./
RUN go mod download

COPY opensearch-pricing-calculator/ .
RUN CGO_ENABLED=0 GOOS=linux GOARCH=${TARGETARCH} \
    go build -a -installsuffix cgo -o opensearch-pricing-calculator .

# --- Stage 2: Runtime ---
FROM ${RUNTIME_IMAGE}

RUN dnf install -y --setopt=install_weak_deps=False \
      ca-certificates \
      curl \
      jq \
      python3.11 \
      python3.11-pip \
      shadow-utils \
    && dnf clean all && rm -rf /var/cache/dnf

# Create non-root user for Claude agent
RUN useradd -m -s /bin/bash user

# --- Pricing Calculator ---
WORKDIR /app

COPY --from=go-builder /build/opensearch-pricing-calculator .
COPY --from=go-builder /build/priceCache.json .
COPY build/version/VERSION .

# Python skill (pip install for programmatic use)
COPY skills/solr-opensearch-migration-advisor /app/skills/solr-opensearch-migration-advisor
RUN python3.11 -m pip install --no-cache-dir \
    /app/skills/solr-opensearch-migration-advisor

RUN mkdir -p /app/logs && chown -R user:user /app

# --- Claude Agent ---
RUN mkdir -p /home/user/claude/.claude/skills/solr-opensearch-migration-advisor \
    && mkdir -p /home/user/claude/processing/.claude/skills

COPY skills/solr-opensearch-migration-advisor/entrypoint.sh /home/user/entrypoint.sh
COPY skills/solr-opensearch-migration-advisor/SKILL.md /home/user/claude/.claude/skills/solr-opensearch-migration-advisor/SKILL.md
COPY skills/solr-opensearch-migration-advisor/references /home/user/claude/references
COPY skills/solr-opensearch-migration-advisor/scripts /home/user/claude/scripts
COPY skills/solr-opensearch-migration-advisor/steering /home/user/claude/steering

RUN chmod +x /home/user/entrypoint.sh \
    && chown -R user:user /home/user/claude

USER user
WORKDIR /home/user

# Install Claude CLI
RUN curl -fsSL https://claude.ai/install.sh | bash
ENV PATH="/home/user/.local/bin:${PATH}"
RUN claude --version

# Main API port + MCP server port
EXPOSE 5050
EXPOSE 8081

CMD ["tail", "-f", "/dev/null"]
