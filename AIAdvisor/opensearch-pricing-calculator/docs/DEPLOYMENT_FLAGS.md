# Tool Use & LLM Feature Flags Deployment Guide

## Overview

The OpenSearch Calculator service now supports **Bedrock Tool Calling API** and **forced LLM path** for improved Assistant functionality. These features are controlled via environment variables in the ECS task definition.

## Changes Made

### 1. CDK Task Definition (`cdk-deployment/lib/opensearch-calculator-stack.ts:255-260`)

Environment variables added to the ECS container:

```typescript
environment: {
  PORT: '8080',
  SERVICE_NAME: 'opensearch-calculator',
  USE_TOOL_CALLING: 'true',    // Enable Bedrock Tool Calling API
  FORCE_LLM_PATH: 'true',       // Force LLM path for all Assistant queries
},
```

### 2. CORS Middleware (`middleware.go:9-24`)

Added support for `X-Session-ID` header to enable multi-turn conversations:

```go
// Expose X-Session-ID so frontend can read it from response
writer.Header().Set("Access-Control-Expose-Headers", "X-Session-ID")

// Allow X-Session-ID header to be sent from frontend
writer.Header().Set("Access-Control-Allow-Headers",
  "Accept, Content-Type, X-CSRF-Token, Authorization, X-Session-ID")
```

### 3. Deployment Script (`cdk-deployment/deploy-application.sh`)

The script now:
- Displays feature flag status at startup
- Optionally updates the task definition via CDK before deploying
- Ensures environment variables are always up-to-date in production

## Deployment Options

### Standard Deployment (Recommended)

This updates the task definition AND deploys the application:

```bash
cd cdk-deployment
./deploy-application.sh
```

**What it does:**
1. ✅ Updates ECS task definition with `USE_TOOL_CALLING=true` and `FORCE_LLM_PATH=true`
2. ✅ Builds ARM64 Linux binary
3. ✅ Builds and pushes Docker image to ECR
4. ✅ Deploys new ECS tasks with updated environment variables

### Fast Deployment (Skip Task Definition Update)

If you only changed the application code and don't need env var updates:

```bash
cd cdk-deployment
UPDATE_TASK_DEFINITION=false ./deploy-application.sh
```

This skips the CDK deploy step (~30 seconds faster).

### Infrastructure-Only Update

To update just the task definition without deploying new code:

```bash
cd cdk-deployment
npm install
npx cdk deploy
```

## How It Works

### Environment Variable Flow

```
CDK Stack (TypeScript)
  ↓
ECS Task Definition
  ↓
Container Environment
  ↓
Go Application reads os.Getenv("USE_TOOL_CALLING")
```

### Feature Behavior

**When `USE_TOOL_CALLING=true`:**
- LLM uses Bedrock's native Tool Calling API
- Structured `tool_use` blocks instead of JSON parsing
- Automatic parameter validation against MCP schema
- More reliable and consistent than prompt-based approach

**When `FORCE_LLM_PATH=true`:**
- ALL Assistant queries go through LLM (bypasses NLP)
- Ensures best quality responses for all queries
- Enables full conversation context awareness
- Required for multi-turn conversations

## Verification

### Check Task Definition

View current environment variables:

```bash
CLUSTER_NAME="os-calculator-cluster"
TASK_DEF=$(aws ecs list-task-definitions \
  --query 'taskDefinitionArns[?contains(@, `Calculator`)]' \
  --output text | head -1)

aws ecs describe-task-definition \
  --task-definition $TASK_DEF \
  --query 'taskDefinition.containerDefinitions[0].environment' \
  --output table
```

Expected output:
```
------------------------------------------------------
|              environment                           |
+-------------------------+--------------------------+
|  name                   |  value                   |
+-------------------------+--------------------------+
|  PORT                   |  8080                    |
|  SERVICE_NAME           |  opensearch-calculator   |
|  USE_TOOL_CALLING       |  true                    |
|  FORCE_LLM_PATH         |  true                    |
+-------------------------+--------------------------+
```

### Check Runtime Logs

Verify Tool Use is active in ECS logs:

```bash
aws logs tail /ecs/os-calculator-service --follow --format short | grep "Tool"
```

Expected log entries:
```json
{"message":"Using Bedrock Tool Calling API","approach":"tool_use","USE_TOOL_CALLING":"true"}
```

### Test via API

```bash
API_URL="https://your-api-gateway-url/prod"

curl -X POST $API_URL/api/assistant/estimate \
  -H "Content-Type: application/json" \
  -d '{"query": "Vector search with 10M vectors, 768 dimensions"}' \
  | jq .
```

Expected: Response should include cost estimates with `metadata.parsedWithLLM: true`.

## Changing Feature Flags

### To Disable Tool Use (Use prompt-based approach instead)

1. **Update CDK stack:**

```typescript
// In opensearch-calculator-stack.ts
environment: {
  PORT: '8080',
  SERVICE_NAME: 'opensearch-calculator',
  USE_TOOL_CALLING: 'false',     // Changed to false
  FORCE_LLM_PATH: 'true',
},
```

2. **Deploy:**

```bash
cd cdk-deployment
./deploy-application.sh
```

### To Disable Forced LLM Path (Use SmartRouter logic)

```typescript
environment: {
  PORT: '8080',
  SERVICE_NAME: 'opensearch-calculator',
  USE_TOOL_CALLING: 'true',
  FORCE_LLM_PATH: 'false',        // Changed to false
},
```

With `FORCE_LLM_PATH=false`, the SmartRouter will:
- Use LLM for low-confidence queries (<0.90)
- Use LLM for conversational queries
- Use fast NLP path for high-confidence queries (>=0.90)

## Rollback

If issues arise, rollback to previous task definition:

```bash
CLUSTER_NAME="os-calculator-cluster"
SERVICE_NAME="os-calculator-service"

# List recent task definitions
aws ecs list-task-definitions \
  --family-prefix OpenSearchCalculatorStack \
  --query 'taskDefinitionArns[-5:]' \
  --output table

# Rollback to previous version (e.g., revision 3)
aws ecs update-service \
  --cluster $CLUSTER_NAME \
  --service $SERVICE_NAME \
  --task-definition OpenSearchCalculatorStackCalculatorTaskDefinition:3
```

## Best Practices

1. **Always test in dev first** before production deployment
2. **Monitor CloudWatch logs** after deployment for any errors
3. **Use the standard deployment** (with task definition update) for production
4. **Keep feature flags in CDK** (not Dockerfile) for easier configuration management
5. **Document any flag changes** in git commit messages

## Troubleshooting

### Issue: Task Definition Not Updated

**Symptom:** Logs don't show "Using Bedrock Tool Calling API"

**Solution:**
```bash
# Force a full infrastructure redeploy
cd cdk-deployment
npx cdk deploy --force

# Then redeploy application
./deploy-application.sh
```

### Issue: Environment Variables Not Visible in Container

**Symptom:** `os.Getenv("USE_TOOL_CALLING")` returns empty string

**Solution:**
```bash
# Check task definition has the variables
aws ecs describe-task-definition \
  --task-definition <task-def-arn> \
  --query 'taskDefinition.containerDefinitions[0].environment'

# If missing, redeploy CDK stack
cd cdk-deployment
npx cdk deploy
```

### Issue: Old Tasks Still Running

**Symptom:** Some tasks have old environment variables

**Solution:**
```bash
# Force new deployment to restart all tasks
aws ecs update-service \
  --cluster os-calculator-cluster \
  --service os-calculator-service \
  --force-new-deployment
```

## Additional Resources

- [Bedrock Tool Use Documentation](https://docs.aws.amazon.com/bedrock/latest/userguide/tool-use.html)
- [ECS Task Definition Docs](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/task_definitions.html)
- [Implementation Status Document](/tmp/IMPLEMENTATION_STATUS.md)
