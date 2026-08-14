/**
 * MD 文档版本相关常量与工具函数
 * 提供版本挡板数据，后续接入真实接口时可替换 loadMdVersions 的实现
 */

/**
 * MD 文档版本挡板数据（后续接真实接口可替换 loadMdVersions）
 * versionNo 仍用 YYYYMM，与后端校验一致；id 为数字主键
 */
export const MD_VERSION_MOCK = [
  { id: 1, versionNo: '202608', label: '2026年8月份版本' },
  { id: 2, versionNo: '202609', label: '2026年9月份版本' }
]

/**
 * 按文档类型返回版本列表（目前概要 / 详细共用同一套挡板）
 * @param {string} documentType 文档类型
 * @param {string} application 应用标识
 * @returns {Promise<Array>} 返回版本列表的 Promise（挡板数据的浅拷贝）
 */
export function loadMdVersions (documentType, application) {
  void documentType
  void application
  return Promise.resolve(MD_VERSION_MOCK.map(item => ({ ...item })))
}
