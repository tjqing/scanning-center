/**
 * 接口请求模块（API）
 * 集中封装后台所有业务模块的 HTTP 请求方法，统一通过 utils/request 发起请求
 */

// 引入封装的 axios 请求实例
import request from '../utils/request';

/**
 * 规则（Rule）相关接口
 */
export const ruleApi = {
  /** 分页查询规则列表 */
  page: p => request.get('/rules', { params: p }),
  /** 根据 ID 查询单个规则 */
  get: id => request.get('/rules/' + id),
  /** 新增规则 */
  create: d => request.post('/rules', d),
  /** 根据 ID 更新规则 */
  update: (id, d) => request.put('/rules/' + id, d),
  /** 根据 ID 删除规则 */
  remove: id => request.delete('/rules/' + id),
  /** 复制规则 */
  copy: id => request.post('/rules/' + id + '/copies'),
  /** 启用 / 停用规则 */
  status: (id, v) => request.put('/rules/' + id + '/status', {}, { params: { enabled: v } })
};

/**
 * 代码仓库（Repository）相关接口
 */
export const repoApi = {
  /** 分页查询仓库列表 */
  page: p => request.get('/repositories', { params: p }),
  /** 根据 ID 查询单个仓库 */
  get: id => request.get('/repositories/' + id),
  /** 新增仓库 */
  create: d => request.post('/repositories', d),
  /** 根据 ID 更新仓库 */
  update: (id, d) => request.put('/repositories/' + id, d),
  /** 根据 ID 删除仓库 */
  remove: id => request.delete('/repositories/' + id),
  /** 启用 / 停用仓库 */
  status: (id, v) => request.put('/repositories/' + id + '/status', {}, { params: { enabled: v } }),
  /** 查询指定文档类型与应用的 MD 版本列表 */
  mdVersions: (documentType, application) => request.get('/repositories/md/versions', { params: { documentType, application } }),
  /** 上传文件到指定仓库 */
  upload: (id, f) => {
    // 构造 FormData 表单数据并追加文件
    const d = new FormData();
    d.append('file', f);
    return request.post('/repositories/' + id + '/files', d);
  }
};

/**
 * 扫描任务（Task）相关接口
 */
export const taskApi = {
  /** 分页查询任务列表 */
  page: p => request.get('/tasks', { params: p }),
  /** 根据 ID 查询单个任务 */
  get: id => request.get('/tasks/' + id),
  /** 新增任务 */
  create: d => request.post('/tasks', d),
  /** 根据 ID 更新任务 */
  update: (id, d) => request.put('/tasks/' + id, d),
  /** 复制任务 */
  copy: id => request.post('/tasks/' + id + '/copies'),
  /** 运行任务 */
  run: id => request.post('/tasks/' + id + '/runs'),
  /** 停止任务 */
  stop: id => request.put('/tasks/' + id + '/stop'),
  /** 取消任务（与 stop 同接口） */
  cancel: id => request.put('/tasks/' + id + '/stop'),
  /** 恢复任务 */
  resume: id => request.put('/tasks/' + id + '/resume'),
  /** 终止任务 */
  abort: id => request.put('/tasks/' + id + '/abort'),
  /** 根据 ID 删除任务 */
  remove: id => request.delete('/tasks/' + id),
  /** 查询任务快照列表 */
  snapshots: id => request.get('/tasks/' + id + '/snapshots'),
  /** 查询任务当前清单 */
  manifest: id => request.get('/tasks/' + id + '/manifests/current'),
  /** 分页查询清单文件 */
  manifestFiles: (id, p) => request.get('/tasks/manifests/' + id + '/files', { params: p }),
  /** 查询清单文件中某文件的 AI 响应 */
  fileAiResponses: (taskId, fileId) => request.get('/tasks/' + taskId + '/manifest-files/' + fileId + '/ai-responses'),
  /** 重新生成任务清单 */
  regenerateManifest: id => request.post('/tasks/' + id + '/manifests')
};

/**
 * 扫描结果（Result）相关接口
 */
export const resultApi = {
  /** 分页查询结果列表 */
  page: p => request.get('/results', { params: p }),
  /** 根据 ID 查询单个结果 */
  get: id => request.get('/results/' + id),
  /** 分页查询问题列表 */
  issues: p => request.get('/issues', { params: p }),
  /** 根据 ID 查询单个问题 */
  issue: id => request.get('/issues/' + id),
  /** 更新问题处理状态 */
  status: (id, d) => request.put('/issues/' + id + '/status', d),
  /** 导出问题清单（返回 blob 二进制流） */
  exportIssues: p => request.get('/issues/export', { params: p, responseType: 'blob' })
};

/**
 * 开发态登录 / 当前用户
 */
export const authApi = {
  login: d => request.post('/auth/login', d),
  me: () => request.get('/auth/me'),
  logout: () => request.post('/auth/logout')
};

/**
 * 用户（User）相关接口
 */
export const userApi = {
  /** 分页查询用户列表 */
  page: p => request.get('/users', { params: p }),
  /** 根据 ID 查询单个用户 */
  get: id => request.get('/users/' + id),
  /** 新增用户 */
  create: d => request.post('/users', d),
  /** 根据 ID 更新用户 */
  update: (id, d) => request.put('/users/' + id, d),
  /** 根据 ID 删除用户 */
  remove: id => request.delete('/users/' + id),
  /** 启用 / 停用用户 */
  status: (id, v) => request.put('/users/' + id + '/status', {}, { params: { enabled: v } })
};

/**
 * 仓库目录（RepositoryCatalog）相关接口
 */
export const repositoryCatalogApi = {
  /** 分页查询仓库目录列表 */
  page: p => request.get('/repository-catalogs', { params: p }),
  /** 查询指定应用可用的目录 */
  available: application => request.get('/repository-catalogs/available', { params: { application } }),
  /** 查询当前用户拥有的目录 */
  mine: () => request.get('/repository-catalogs/mine'),
  /** 根据 ID 查询单个目录 */
  get: id => request.get('/repository-catalogs/' + id),
  /** 新增目录 */
  create: d => request.post('/repository-catalogs', d),
  /** 根据 ID 更新目录 */
  update: (id, d) => request.put('/repository-catalogs/' + id, d),
  /** 根据 ID 删除目录 */
  remove: id => request.delete('/repository-catalogs/' + id),
  /** 启用 / 停用目录 */
  status: (id, v) => request.put('/repository-catalogs/' + id + '/status', {}, { params: { enabled: v } })
};

/**
 * 模型（Model）相关接口
 */
export const modelApi = {
  /** 查询模型凭据列表 */
  credentials: () => request.get('/model-credentials'),
  /** 新增模型凭据 */
  createCredential: d => request.post('/model-credentials', d),
  /** 根据 ID 更新模型凭据 */
  updateCredential: (id, d) => request.put('/model-credentials/' + id, d),
  /** 根据 ID 删除模型凭据 */
  removeCredential: id => request.delete('/model-credentials/' + id),
  /** 启用 / 停用模型凭据 */
  credentialStatus: (id, v) => request.put('/model-credentials/' + id + '/status', {}, { params: { enabled: v } }),
  /** 测试模型凭据连接 */
  testCredential: id => request.post('/model-credentials/' + id + '/connection-test'),
  /** 分页查询模型提示词列表 */
  prompts: p => request.get('/model-prompts', { params: p }),
  /** 新增模型提示词 */
  createPrompt: d => request.post('/model-prompts', d),
  /** 根据 ID 更新模型提示词 */
  updatePrompt: (id, d) => request.put('/model-prompts/' + id, d),
  /** 激活指定的模型提示词 */
  activatePrompt: id => request.put('/model-prompts/' + id + '/activation'),
  /** 查询 Token 重试次数配置 */
  tokenRetryCount: () => request.get('/model-settings/token-retry-count'),
  /** 更新 Token 重试次数配置 */
  updateTokenRetryCount: v => request.put('/model-settings/token-retry-count', {}, { params: { retryCount: v } }),
  /** 查询 AI 扫描并发数配置 */
  aiScanConcurrency: () => request.get('/model-settings/ai-scan-concurrency'),
  /** 更新 AI 扫描并发数配置 */
  updateAiScanConcurrency: v => request.put('/model-settings/ai-scan-concurrency', {}, { params: { concurrency: v } }),
  /** 查询 AI 调度窗口配置 */
  aiScheduleWindow: () => request.get('/model-settings/ai-schedule-window'),
  /** 更新 AI 调度窗口配置 */
  updateAiScheduleWindow: (start, end) => request.put('/model-settings/ai-schedule-window', {}, { params: { start, end } })
};
