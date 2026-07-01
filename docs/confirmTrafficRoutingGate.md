# confirmtrafficrouting Approval Gate 实现文档

## 背景

在 opensearch-migrations 的 Capture & Replay 零停机迁移中，workflow submit 后，capture proxy ready 之后会立即开始打 snapshot。但用户需要时间将生产流量切换到 capture proxy 上——如果 snapshot 在流量切换之前开始，那么 snapshot 时间点到流量切换之间的写入将既不在 snapshot 中，也不会被 proxy capture，导致数据丢失。

本修改在 proxy ready 和 snapshot 之间添加了一个 `confirmtrafficrouting` approval gate，workflow 会暂停等待用户确认流量已经切换到 capture proxy 上。

## 代码结构

```
orchestrationSpecs/
├── packages/
│   ├── migration-workflow-templates/src/workflowTemplates/
│   │   ├── fullMigration.ts          # 主 workflow 编排（定义步骤顺序）
│   │   ├── resourceManagement.ts     # 提供 waitForUserApproval 原语模板
│   │   ├── metadataMigration.ts      # metadata 迁移（现有 approval gate 参考）
│   │   └── setupCapture.ts           # proxy 部署模板
│   └── config-processor/src/
│       ├── migrationInitializer.ts   # 生成 K8s 自定义资源（含 ApprovalGate CR）
│       └── formatApprovals.ts        # skipApprovals 逻辑（决定哪些 gate 自动跳过）
```

### 关键机制

1. **ApprovalGate CRD**：K8s 自定义资源，状态 `phase` 从 `Created` → `Approved`（用户手动批准）
2. **waitForUserApproval 模板**（resourceManagement.ts）：Argo Workflow 步骤，持续轮询 ApprovalGate CR 直到 `status.phase == Approved`
3. **migrationInitializer.ts**：在 workflow submit 前生成所有需要的 ApprovalGate CR
4. **formatApprovals.ts**：当 `skipApprovals: true` 时，生成 autoApprove configmap 使 gate 自动跳过

## 修改方案

### 原始 workflow 步骤顺序（createSingleSnapshot 模板）

```
reconcileDataSnapshotResource
  → readSnapshotPhase
  → waitIndefinitelyForProxyDeps    // 等 proxy CR phase==Ready
  → waitIndefinitelyForSnapshot     // 若已 Pending，等完成
  → createOrGetSnapshot             // 触发 snapshot
```

### 修改后的步骤顺序

```
reconcileDataSnapshotResource
  → readSnapshotPhase
  → waitIndefinitelyForProxyDeps    // 等 proxy CR phase==Ready
  → waitForTrafficRoutingApproval   // ★ 新增：等用户确认流量已切换
  → waitIndefinitelyForSnapshot
  → createOrGetSnapshot
```

## 修改的文件

### 1. `fullMigration.ts`（workflow 模板）

在 `createSingleSnapshot` 模板的 `waitIndefinitelyForProxyDeps` 步骤之后添加新步骤：

```typescript
.addStep("waitForTrafficRoutingApproval", ResourceManagement, "waitForUserApproval", c =>
    c.register({
        resourceName: expr.concat(
            expr.literal("confirmtrafficrouting."),
            b.inputs.resourceName
        ),
    }),
    {when: c => ({templateExp: expr.and(
        expr.not(expr.and(
            expr.equals(c.readSnapshotPhase.outputs.phase, "Completed"),
            expr.equals(c.readSnapshotPhase.outputs.configChecksum, b.inputs.configChecksum)
        )),
        expr.not(expr.and(
            expr.equals(c.readSnapshotPhase.outputs.phase, "Pending"),
            expr.equals(c.readSnapshotPhase.outputs.configChecksum, b.inputs.configChecksum)
        ))
    )})}
)
```

- **gate 名称**：`confirmtrafficrouting.{resourceName}`，如 `confirmtrafficrouting.source-main`
- **when 条件**：仅当 snapshot 尚未完成且未处于 Pending 状态时等待（与 waitIndefinitelyForProxyDeps 相同条件）
- 如果 snapshot 已经完成（resubmit 场景），此步骤会被跳过

### 2. `migrationInitializer.ts`（CR 生成）

在 DataSnapshot 资源循环中，当该 snapshot 有 proxy 依赖时生成对应 ApprovalGate CR：

```typescript
if ((item as any).dependsOnProxySetups?.length > 0) {
    items.push(this.makeApprovalGateResource(
        ['confirmtrafficrouting', snapshotResourceName],
        gateLabels({...})
    ));
}
```

- 仅在有 `dependsOnProxySetups`（即配置了 capture proxy 的迁移）时生成
- 纯 backfill 迁移（无 proxy）不会产生此 gate

### 3. `formatApprovals.ts`（skip 逻辑）

当全局或单个迁移设置 `skipApprovals: true` 时，自动跳过此 gate：

```typescript
...( skipAll ? { confirmTrafficRouting: true } : {}),
```

## 使用方式

### 正常流程

```bash
# 1. 提交 workflow
workflow submit

# 2. 等待 proxy ready（workflow 自动完成）
workflow status
# 看到: waitForTrafficRoutingApproval - WAITING FOR APPROVAL

# 3. 获取 proxy service 地址
kubectl get svc -n ma | grep proxy

# 4. 将应用流量切换到 proxy service

# 5. 确认流量正常后，批准 gate
workflow approve confirmtrafficrouting.source-main

# 6. Workflow 继续：开始打 snapshot → backfill → replay
```

### 跳过此 gate

在配置中设置 `skipApprovals: true`：
```json
{
  "skipApprovals": true,
  ...
}
```

## 编译和测试

```bash
cd orchestrationSpecs
npm ci
npm run type-check    # 类型检查
npx jest              # 运行测试
npx jest -u           # 如修改了模板，更新 snapshot 测试
```
