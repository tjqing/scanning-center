/**
 * 扫描作用域静态预设（前端维护）
 * 以彩色可关闭标签多选，并集回填 fileTypes / excludePatterns
 */

/** 扫描作用域预设列表 */
export const SCOPE_PRESETS = [
  {
    key: 'frontend',
    name: '前端（Vue）',
    shortName: '前端',
    tagType: 'success',
    fileTypes: 'vue',
    excludePatterns: 'target,node_modules,public'
  },
  {
    key: 'backend',
    name: '后端（SQL）',
    shortName: '后端',
    tagType: 'warning',
    fileTypes: 'sql',
    excludePatterns: 'sql,target'
  }
]

/**
 * 根据 key 查找对应的作用域预设
 * @param {string} key 预设的 key
 * @returns {Object|null} 匹配到的预设对象，未找到时返回 null
 */
export function findScopePreset (key) {
  return SCOPE_PRESETS.find(item => item.key === key) || null
}

/**
 * 多选预设按并集合并（去重、保序）
 * @param {Array<string>} keys 选中的预设 key 列表
 * @returns {{fileTypes: string, excludePatterns: string}} 合并后的文件类型与排除模式（逗号分隔）
 */
export function mergeScopePresets (keys) {
  const list = (keys || []).map(findScopePreset).filter(Boolean)
  const types = []
  const excludes = []
  list.forEach(item => {
    splitCsv(item.fileTypes).forEach(v => { if (types.indexOf(v) < 0) types.push(v) })
    splitCsv(item.excludePatterns).forEach(v => { if (excludes.indexOf(v) < 0) excludes.push(v) })
  })
  return {
    fileTypes: types.join(','),
    excludePatterns: excludes.join(',')
  }
}

/**
 * 将逗号分隔的字符串拆分为去空格、去空项的数组成员
 * @param {string} value 逗号分隔的字符串
 * @returns {Array<string>} 清洗后的字符串数组
 */
function splitCsv (value) {
  return String(value || '').split(',').map(s => s.trim()).filter(Boolean)
}
