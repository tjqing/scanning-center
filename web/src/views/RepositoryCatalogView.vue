<template>
  <div class="page">
    <div class="toolbar">
      <el-input v-model="q.keyword" clearable placeholder="代码库名称或地址" @keyup.enter.native="load" />
      <el-select v-model="q.application" clearable placeholder="应用"><el-option v-for="app in applications" :key="app" :label="app" :value="app" /></el-select>
      <el-button type="primary" @click="load">查询</el-button><el-button type="success" @click="edit()">新增代码库</el-button>
    </div>
    <el-table :data="rows" v-loading="loading">
      <el-table-column prop="repositoryName" label="代码库名称" min-width="180" />
      <el-table-column prop="repositoryUrl" label="代码库地址" min-width="280" show-overflow-tooltip />
      <el-table-column prop="application" label="应用" width="110" />
      <el-table-column label="版本" width="150">
        <template slot-scope="s">{{ versionLabel(s.row.versionNo) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="90"><template slot-scope="s"><el-switch :value="s.row.enabled" @change="status(s.row,$event)" /></template></el-table-column>
      <el-table-column label="操作" width="170"><template slot-scope="s"><el-button size="mini" @click="edit(s.row)">编辑</el-button><el-button size="mini" type="danger" @click="remove(s.row)">删除</el-button></template></el-table-column>
    </el-table>
    <el-pagination class="pager" layout="total,prev,pager,next" :total="total" :page-size="q.pageSize" @current-change="p=>{q.pageNum=p;load()}" />
    <el-dialog :title="form.id?'编辑代码库':'新增代码库'" :visible.sync="visible" width="680px">
      <el-form :model="form" label-width="110px">
        <el-form-item label="代码库名称" required><el-input v-model="form.repositoryName" placeholder="例如 group/project" /></el-form-item>
        <el-form-item label="代码库地址" required>
          <el-input v-model="form.repositoryUrl" placeholder="git@host:group/repo.git 或 https://host/group/repo.git" />
          <div class="hint">拉取默认使用库内已配置的 SSH 私钥；若填 https://，克隆时会自动转成 SSH 地址</div>
        </el-form-item>
        <el-form-item label="应用" required>
          <el-select v-model="form.application" style="width:100%" @change="applicationChanged(false)">
            <el-option v-for="app in applications" :key="app" :label="app" :value="app" />
          </el-select>
        </el-form-item>
        <el-form-item label="版本" required>
          <el-select v-model="form.versionNo" style="width:100%" :loading="versionsLoading" placeholder="请选择版本" clearable :disabled="!form.application">
            <el-option v-for="version in versions" :key="version.id" :label="version.label" :value="version.versionNo" />
          </el-select>
        </el-form-item>
        <el-form-item label="启用"><el-switch v-model="form.enabled" /></el-form-item>
      </el-form>
      <span slot="footer"><el-button @click="visible=false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></span>
    </el-dialog>
  </div>
</template>
<script>
import { repositoryCatalogApi } from '../api'
import { APPLICATIONS } from '../constants/applications'
import { loadMdVersions, MD_VERSION_MOCK } from '../constants/mdVersions'
const empty = () => ({
  repositoryName: '', repositoryUrl: '', application: '', versionNo: '', enabled: true
})
export default {
  name: 'RepositoryCatalogView',
  data: () => ({
    applications: APPLICATIONS, versions: [], versionsLoading: false,
    q: { keyword: '', application: '', pageNum: 1, pageSize: 20 },
    rows: [], total: 0, loading: false, saving: false, visible: false, form: empty()
  }),
  created () { this.load() },
  methods: {
    /** 根据版本号查找并返回版本标签名称，未配置时返回 '-' */
    versionLabel (versionNo) {
      if (!versionNo) return '-'
      const hit = MD_VERSION_MOCK.find(item => item.versionNo === versionNo)
      return hit ? hit.label : versionNo
    },
    /** 分页加载代码库列表 */
    async load () { this.loading = true; try { const result = await repositoryCatalogApi.page(this.q); this.rows = result.list; this.total = result.total } finally { this.loading = false } },
    /** 打开新增/编辑弹窗：初始化表单并加载应用对应的版本 */
    async edit (row) {
      this.form = row ? Object.assign(empty(), row) : empty()
      this.versions = []
      this.visible = true
      if (this.form.application) await this.applicationChanged(true)
    },
    /** 应用变化时加载版本列表；preserve 为 true 时尽量保留已选版本 */
    async applicationChanged (preserve) {
      const selected = preserve ? this.form.versionNo : ''
      this.form.versionNo = ''
      this.versions = []
      if (!this.form.application) return
      this.versionsLoading = true
      try {
        this.versions = await loadMdVersions(null, this.form.application)
        if (selected && this.versions.some(item => item.versionNo === selected)) this.form.versionNo = selected
      } finally { this.versionsLoading = false }
    },
    /** 校验并保存代码库（新增或更新） */
    async save () {
      // 校验代码库名称格式（xxx/xxx）
      if (!/^[^/\s]+\/[^/\s]+$/.test(this.form.repositoryName)) return this.$message.warning('代码库名称必须使用xxx/xxx格式')
      // 校验代码库地址必填
      if (!this.form.repositoryUrl.trim()) return this.$message.warning('请填写代码库地址')
      const url = this.form.repositoryUrl.trim()
      // 校验地址协议格式
      if (!(url.startsWith('git@') || url.startsWith('ssh://') || url.startsWith('http://') || url.startsWith('https://'))) {
        return this.$message.warning('请填写 git@host:group/repo.git 或 https:// 地址')
      }
      // 校验应用与版本必填
      if (!this.form.application) return this.$message.warning('请选择应用')
      if (!this.form.versionNo || !/^\d{6}$/.test(this.form.versionNo)) return this.$message.warning('请选择版本')
      // 组装提交参数，认证方式固定为 SSH_KEY（私钥由后端 applyGitCredential 处理）
      const payload = {
        repositoryName: this.form.repositoryName,
        repositoryUrl: this.form.repositoryUrl,
        application: this.form.application,
        versionNo: this.form.versionNo,
        authType: 'SSH_KEY',
        enabled: this.form.enabled
      }
      this.saving = true
      try {
        if (this.form.id) await repositoryCatalogApi.update(this.form.id, payload)
        else await repositoryCatalogApi.create(payload)
        this.visible = false
        this.$message.success('代码库保存成功')
        await this.load()
      } finally { this.saving = false }
    },
    /** 启用 / 停用代码库 */
    async status (row, enabled) { await repositoryCatalogApi.status(row.id, enabled); row.enabled = enabled },
    /** 删除代码库：二次确认后删除并刷新 */
    async remove (row) { await this.$confirm('确认删除代码库 ' + row.repositoryName + '？'); await repositoryCatalogApi.remove(row.id); this.$message.success('删除成功'); await this.load() }
  }
}
</script>
<style scoped>
.hint { color: #909399; font-size: 12px; line-height: 1.5; margin-top: 4px; }
</style>
