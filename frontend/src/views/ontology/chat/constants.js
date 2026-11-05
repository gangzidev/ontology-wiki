/**
 * 分析校对助手 — 业务常量
 * 集中管理所有魔法字符串，避免散落在组件各处。
 */

// ── 知识范围类型 ──────────────────────────────────────────
export const SCOPE_TYPE = {
  ALL: 'ALL',           // 全部 Wiki 资料
  SPACE: 'SPACE',       // 行业知识空间
  ASSET: 'ASSET',       // 单个 Wiki 资产
  ANALYSIS: 'ANALYSIS', // 分析任务（校对模式）
}

/** 需要动态远程搜索目标的范围类型 */
export const DYNAMIC_SCOPE_TYPES = [SCOPE_TYPE.ASSET, SCOPE_TYPE.ANALYSIS]

/** 范围类型的显示配置 */
export const SCOPE_TYPE_CONFIG = {
  [SCOPE_TYPE.ALL]: {
    label: '全部 Wiki 资料',
    icon: 'el-icon-files',
    hint: '使用所有已整理的 Wiki 资料回答，不限行业。',
  },
  [SCOPE_TYPE.SPACE]: {
    label: '行业知识空间',
    icon: 'el-icon-folder-opened',
    hint: '仅使用所选行业空间内的资产和 Wiki 页面。',
  },
  [SCOPE_TYPE.ASSET]: {
    label: '单个 Wiki 资产',
    icon: 'el-icon-document',
    hint: '仅围绕指定资产及其关联资料回答。',
  },
  [SCOPE_TYPE.ANALYSIS]: {
    label: '分析任务',
    icon: 'el-icon-data-analysis',
    hint: '仅围绕当前分析草案问答；不检索 Wiki 页面，也不执行本体逻辑或动作。',
  },
}

// ── 分析保存状态 ──────────────────────────────────────────
export const ANSWER_STATUS = {
  CONFIRMED: 'CONFIRMED',
  DRAFT: 'DRAFT',
}

// ── 枚举映射表（供 analysisTextUtils 引用） ───────────────

export const RISK_LEVEL_MAP = {
  LOW: '低', MEDIUM: '中', HIGH: '高', CRITICAL: '严重',
}

export const CANDIDATE_STATUS_MAP = {
  NEEDS_REVIEW: '待审核', DRAFT: '待审核', REVIEWED: '待审核',
  APPROVED: '已通过', REJECTED: '已驳回',
  IMPORTED: '已导入', ACTIVE: '已启用', DISABLED: '已停用',
}

export const OPERATION_MAP = {
  READ: '查询', SELECT: '查询', INSERT: '新增', UPDATE: '更新', DELETE: '删除',
}

export const TRIGGER_MAP = {
  MCP_REQUEST: '工具 / API 请求',
  MANUAL: '人工触发',
  SCHEDULE: '定时触发',
  EVENT: '事件触发',
  WEBHOOK: 'Webhook 回调',
}

export const CARDINALITY_MAP = {
  ONE_TO_ONE: '一对一', ONE_TO_MANY: '一对多',
  MANY_TO_ONE: '多对一', MANY_TO_MANY: '多对多',
}

export const SUBJECT_TYPE_MAP = {
  ROLE: '角色', USER: '用户', GROUP: '用户组', SERVICE: '服务账号',
}

export const ASSET_TYPE_MAP = {
  TEXT: '文本', FILE: '文件', WIKI_PAGE: 'Wiki 页面', URL: '网页链接',
}

// ── 后端资源路径映射 ──────────────────────────────────────
export const SCOPE_RESOURCE_MAP = {
  [SCOPE_TYPE.ASSET]: 'assets',
  [SCOPE_TYPE.ANALYSIS]: 'analyses',
  [SCOPE_TYPE.SPACE]: 'spaces',
}

// ── 业务限制 ──────────────────────────────────────────────
/** 单次提问最大字符数 */
export const QUESTION_MAX_LENGTH = 4000

/** 下拉搜索单页最大结果数 */
export const SEARCH_PAGE_SIZE = 20

/** 空间列表最大加载数 */
export const SPACE_PAGE_SIZE = 100
