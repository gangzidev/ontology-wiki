# 实体关联查询配置与验收契约

本文定义数据查询实体关联能力的首期配置边界。只有草案负责人填入真实业务关系并完成审核后，才允许打开 `entityQueryPolicy.enabled`。

## 配置结构

`entityQueryPolicy` 放在本体配置根节点，必须与对象、属性、关系定义一起纳入配置哈希。

```json
{
  "entityQueryPolicy": {
    "enabled": false,
    "allowedObjectIds": [],
    "allowedRelationIds": [],
    "lookupPropertyIds": [],
    "identityPropertyByObject": {},
    "propertiesByObject": {},
    "maxRelations": 8,
    "maxRowsPerRelation": 50
  }
}
```

- `allowedObjectIds`：允许作为根实体或关联结果的对象 ID。
- `allowedRelationIds`：已由业务负责人确认方向、名称和用途的关系 ID。
- `lookupPropertyIds`：可用于根实体查找的属性 ID；只支持参数化精确匹配。
- `identityPropertyByObject`：每个可查询对象对应的唯一标识属性 ID。数据库数据必须能保证其唯一。
- `propertiesByObject`：按对象列出可投影属性 ID，防止默认返回全部字段。
- `maxRelations`、`maxRowsPerRelation`：请求级上限不能超过服务端的硬上限。

首期执行器仅执行根对象到目标对象的一跳直接关系。关系必须有 `id`、`name`、`sourceObjectId`、`targetObjectId`、`sourceColumn`、`targetColumn`，且 `reviewRequired` 不能为 `true`。有 `status` 时只接受 `CONFIRMED`、`APPROVED`、`ACTIVE`、`EXECUTABLE` 或 `VERIFIED`。中间表及二跳关系在配置和结果契约验证完成前不会执行。

## 建议的业务基线

每个实际部署按真实目录填写下表，示例关系不能照搬为授权：

| 根对象 | 关系名称与方向 | 目标对象 | 基数 | 允许返回字段 | 唯一标识 | 责任人 |
|---|---|---|---|---|---|---|
| 待填写 | 例如“创建” | 待填写 | 待确认 | 待填写 | 待填写 | 待填写 |

## 验收问句与预期

| 输入场景 | 预期结果 |
|---|---|
| 唯一匹配的实体 | 返回根对象摘要，并逐关系返回分组 |
| 没有匹配的实体 | `NOT_FOUND`，不得查询关系组 |
| 多个同名实体 | `NEEDS_SELECTION`，返回不超过两个白名单候选，不自动选取 |
| 请求目录外属性或关系 | 拒绝该字段/关系，不拼接 SQL 标识符 |
| 待审核或状态未知关系 | 关系组 `FAILED`，错误与空结果区分 |
| 关系查询失败但其他关系成功 | 整体 `partial=true`，分别保留组状态 |
| 同一配置哈希的分页请求 | 根实体和关系 ID 不变；每组最多 `maxRowsPerRelation` 条 |
| 配置哈希变化 | 要求重新确认查询条件 |

自然语言评测集应由业务负责人补充真实对象别名、关系动词、同名实体、无权限字段和模糊目标对象问句。在该数据集建立前，不应以模型生成 SQL 成功率作为实体关联能力的验收标准。
