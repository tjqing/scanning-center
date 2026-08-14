/**
 * 应用（Application）相关常量
 * 定义系统支持的全部业务应用标识，以及去掉汇总项后的具体应用列表
 */

/** 全部应用标识列表，其中 ALL 表示汇总 / 全部应用 */
export const APPLICATIONS = ['F-BASE', 'F-GMO', 'F-GMRM', 'F-FMPV', 'F-EFM', 'F-SCIS', 'ALL']

/** 具体应用列表（过滤掉汇总项 ALL），用于需要逐个选择具体应用的场景 */
export const CONCRETE_APPLICATIONS = APPLICATIONS.filter(item => item !== 'ALL')
