/**
 * 扫描结果问题（Issue）相关常量与工具函数
 * 提供问题处理状态、风险等级的中文词典，以及相应的标签解析方法
 */

/** 扫描结果明细：处理状态中文词典 */
export const ISSUE_STATUS = {
  PENDING: '待处理',
  CONFIRMED: '已确认',
  RESOLVED: '已处理',
  IGNORED: '已忽略'
}

/** 处理状态下拉选项（由词典生成，value 为状态码，label 为中文名称） */
export const ISSUE_STATUS_OPTIONS = Object.keys(ISSUE_STATUS).map(value => ({
  value,
  label: ISSUE_STATUS[value]
}))

/** 风险等级中文词典 */
export const RISK_LEVEL = {
  HIGH: '高',
  MEDIUM: '中',
  LOW: '低',
  INFO: '提示'
}

/** 风险等级下拉选项（由词典生成，value 为等级码，label 为中文名称） */
export const RISK_LEVEL_OPTIONS = Object.keys(RISK_LEVEL).map(value => ({
  value,
  label: RISK_LEVEL[value]
}))

/**
 * 根据处理状态码获取中文标签
 * @param {string} code 处理状态码
 * @returns {string} 对应的中文标签，未知或空值时返回 '-' 
 */
export function issueStatusLabel (code) {
  return ISSUE_STATUS[code] || code || '-'
}

/**
 * 根据风险等级码获取中文标签
 * @param {string} code 风险等级码
 * @returns {string} 对应的中文标签，未知或空值时返回 '-'
 */
export function riskLevelLabel (code) {
  return RISK_LEVEL[code] || code || '-'
}
