/**
 * 分析结构枚举值 → 中文 label 的纯函数工具集
 * 统一处理大小写不敏感 + 兜底显示。
 * 用于替代 methods 中散落的多个 analysisXxxText() 方法。
 */
import {
  RISK_LEVEL_MAP,
  CANDIDATE_STATUS_MAP,
  OPERATION_MAP,
  TRIGGER_MAP,
  CARDINALITY_MAP,
  SUBJECT_TYPE_MAP,
  ASSET_TYPE_MAP,
} from './constants'

/**
 * 通用枚举转文本
 * @param {Object} map - 枚举映射表
 * @param {*} value    - 原始值
 * @param {string} fallback - 无匹配时的兜底文本
 */
const enumText = (map, value, fallback = '') => {
  if (value == null || value === '') return fallback
  const mapped = map[String(value).toUpperCase()]
  return mapped !== undefined ? mapped : (String(value) || fallback)
}

/** 风险等级：LOW → 低 */
export const riskText = (v) => enumText(RISK_LEVEL_MAP, v, '未评估')

/** 候选项状态：APPROVED → 已通过 */
export const statusText = (v) => enumText(CANDIDATE_STATUS_MAP, v, '未设置')

/** 操作类型：INSERT → 新增 */
export const operationText = (v) => enumText(OPERATION_MAP, v, '未指定')

/** 触发方式：SCHEDULE → 定时触发 */
export const triggerText = (v) => enumText(TRIGGER_MAP, v, '未指定')

/** 关系基数：ONE_TO_MANY → 一对多 */
export const cardinalityText = (v) => enumText(CARDINALITY_MAP, v, '未设置')

/** 主体类型：ROLE → 角色 */
export const subjectTypeText = (v) => enumText(SUBJECT_TYPE_MAP, v, '未指定')

/** 资产类型：WIKI_PAGE → Wiki 页面 */
export const assetTypeText = (v) => enumText(ASSET_TYPE_MAP, v, '资料')

/**
 * 布尔值转中文
 * 支持 true/false 及其字符串形式
 */
export const booleanText = (value) => {
  if (value === true  || String(value).toLowerCase() === 'true')  return '是'
  if (value === false || String(value).toLowerCase() === 'false') return '否'
  return '未设置'
}
