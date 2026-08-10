<template>
  <div class="page">
    <div class="toolbar">
      <el-input v-model="q.keyword" placeholder="用户名或姓名" clearable @keyup.enter.native="load" />
      <el-select v-model="q.roleCode" clearable placeholder="用户类型"><el-option v-for="r in roles" :key="r.value" :label="r.label" :value="r.value" /></el-select>
      <el-select v-model="q.enabled" clearable placeholder="用户状态"><el-option label="启用" :value="true" /><el-option label="停用" :value="false" /></el-select>
      <el-button type="primary" @click="load">查询</el-button><el-button type="success" @click="edit()">新增用户</el-button>
    </div>
    <el-table :data="rows" v-loading="loading">
      <el-table-column prop="username" label="用户名" min-width="120" /><el-table-column prop="displayName" label="姓名" min-width="120" />
      <el-table-column label="用户类型" width="110"><template slot-scope="s">{{ roleName(s.row.roleCode) }}</template></el-table-column>
      <el-table-column prop="application" label="应用" width="110" />
      <el-table-column label="代码库" min-width="260"><template slot-scope="s"><span v-if="!s.row.repositoryNames || !s.row.repositoryNames.length" class="muted">暂无代码库</span><el-tag v-for="name in s.row.repositoryNames" :key="name" size="mini" class="repo-tag">{{ name }}</el-tag></template></el-table-column>
      <el-table-column label="状态" width="90"><template slot-scope="s"><el-switch :value="s.row.enabled" :disabled="s.row.username==='admin'" @change="status(s.row,$event)" /></template></el-table-column>
      <el-table-column label="操作" width="170"><template slot-scope="s"><el-button size="mini" @click="edit(s.row)">编辑</el-button><el-button size="mini" type="danger" :disabled="s.row.username==='admin'" @click="remove(s.row)">删除</el-button></template></el-table-column>
    </el-table>
    <el-pagination class="pager" layout="total,prev,pager,next" :total="total" :page-size="q.pageSize" @current-change="p=>{q.pageNum=p;load()}" />
    <el-dialog :title="form.id?'编辑用户':'新增用户'" :visible.sync="visible" width="680px">
      <el-form :model="form" label-width="110px">
        <el-form-item label="用户名" required><el-input v-model="form.username" :disabled="form.username==='admin'" /></el-form-item>
        <el-form-item label="外部用户ID"><el-input v-model="form.externalUserId" placeholder="测试案例平台用户唯一标识" /></el-form-item>
        <el-form-item label="姓名" required><el-input v-model="form.displayName" /></el-form-item>
        <el-form-item label="用户类型" required><el-select v-model="form.roleCode" @change="roleChanged"><el-option v-for="r in roles" :key="r.value" :label="r.label" :value="r.value" /></el-select></el-form-item>
        <el-form-item label="应用" required><el-select v-model="form.application" style="width:100%" @change="applicationChanged"><el-option v-for="app in applications" :key="app" :label="app" :value="app" /></el-select></el-form-item>
        <el-form-item label="代码库"><div v-loading="repositoriesLoading" class="repository-box"><span v-if="!availableRepositories.length" class="muted">该应用暂无已启用代码库</span><el-tag v-for="repo in availableRepositories" :key="repo.id" class="repo-tag">{{ repo.repositoryName }}</el-tag></div><div class="hint">选择应用后自动关联该应用下全部已启用代码库。</div></el-form-item>
        <el-form-item label="说明"><el-input v-model="form.description" type="textarea" /></el-form-item>
        <el-form-item label="启用"><el-switch v-model="form.enabled" :disabled="form.username==='admin'" /></el-form-item>
      </el-form>
      <span slot="footer"><el-button @click="visible=false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></span>
    </el-dialog>
  </div>
</template>
<script>
import { userApi, repositoryCatalogApi } from '../api'
import { APPLICATIONS } from '../constants/applications'
const empty = () => ({ username: '', externalUserId: '', displayName: '', roleCode: 'USER', application: '', description: '', enabled: true })
export default {
  name: 'UserView',
  data: () => ({ q: { keyword: '', roleCode: '', enabled: null, pageNum: 1, pageSize: 20 }, applications: APPLICATIONS, roles: [{ label: '管理员', value: 'ADMIN' }, { label: '普通用户', value: 'USER' }], rows: [], total: 0, loading: false, visible: false, saving: false, repositoriesLoading: false, availableRepositories: [], form: empty() }),
  created () { this.load() },
  methods: {
    roleName (value) { const role = this.roles.find(item => item.value === value); return role ? role.label : value },
    async load () { this.loading = true; try { const result = await userApi.page(this.q); this.rows = result.list; this.total = result.total } finally { this.loading = false } },
    async edit (row) { this.form = row ? Object.assign(empty(), row, { application: row.application || (row.roleCode === 'ADMIN' ? 'ALL' : '') }) : empty(); this.availableRepositories = []; this.visible = true; if (this.form.application) await this.applicationChanged(this.form.application) },
    async roleChanged (role) { if (role === 'ADMIN') { this.form.application = 'ALL'; await this.applicationChanged('ALL') } },
    async applicationChanged (application) { this.availableRepositories = []; if (!application) return; this.repositoriesLoading = true; try { this.availableRepositories = await repositoryCatalogApi.available(application) } finally { this.repositoriesLoading = false } },
    async save () { if (!/^[A-Za-z][A-Za-z0-9_.-]{2,63}$/.test(this.form.username)) return this.$message.warning('用户名须以字母开头，长度3-64位'); if (!this.form.displayName.trim()) return this.$message.warning('请填写姓名'); if (!this.form.application) return this.$message.warning('请选择应用'); if (this.form.roleCode === 'ADMIN' && this.form.application !== 'ALL') return this.$message.warning('管理员应用必须选择ALL'); this.saving = true; try { if (this.form.id) await userApi.update(this.form.id, this.form); else await userApi.create(this.form); this.visible = false; this.$message.success('用户及代码库关联保存成功'); await this.load() } finally { this.saving = false } },
    async status (row, enabled) { await userApi.status(row.id, enabled); row.enabled = enabled },
    async remove (row) { await this.$confirm('确认删除用户 ' + row.username + '？'); await userApi.remove(row.id); this.$message.success('删除成功'); await this.load() }
  }
}
</script>
<style scoped>.repo-tag{margin:2px 6px 2px 0}.repository-box{min-height:38px;padding:4px 8px;border:1px solid #dcdfe6;border-radius:4px}.muted{color:#909399}.hint{color:#909399;font-size:12px}</style>
