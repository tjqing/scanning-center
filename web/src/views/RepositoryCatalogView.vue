<template>
  <div class="page">
    <div class="toolbar">
      <el-input v-model="q.keyword" clearable placeholder="代码库名称或地址" @keyup.enter.native="load" />
      <el-select v-model="q.application" clearable placeholder="应用"><el-option v-for="app in applications" :key="app" :label="app" :value="app" /></el-select>
      <el-button type="primary" @click="load">查询</el-button><el-button type="success" @click="edit()">新增代码库</el-button>
    </div>
    <el-table :data="rows" v-loading="loading">
      <el-table-column prop="repositoryName" label="代码库名称" min-width="180" /><el-table-column prop="repositoryUrl" label="代码库地址" min-width="300" show-overflow-tooltip />
      <el-table-column prop="application" label="应用" width="120" />
      <el-table-column label="状态" width="90"><template slot-scope="s"><el-switch :value="s.row.enabled" @change="status(s.row,$event)" /></template></el-table-column>
      <el-table-column label="操作" width="170"><template slot-scope="s"><el-button size="mini" @click="edit(s.row)">编辑</el-button><el-button size="mini" type="danger" @click="remove(s.row)">删除</el-button></template></el-table-column>
    </el-table>
    <el-pagination class="pager" layout="total,prev,pager,next" :total="total" :page-size="q.pageSize" @current-change="p=>{q.pageNum=p;load()}" />
    <el-dialog :title="form.id?'编辑代码库':'新增代码库'" :visible.sync="visible" width="620px">
      <el-form :model="form" label-width="110px">
        <el-form-item label="代码库名称" required><el-input v-model="form.repositoryName" placeholder="例如 group/project" /></el-form-item>
        <el-form-item label="代码库地址" required><el-input v-model="form.repositoryUrl" placeholder="例如 https://git.example.com/group/project.git" /></el-form-item>
        <el-form-item label="应用" required><el-select v-model="form.application" style="width:100%"><el-option v-for="app in applications" :key="app" :label="app" :value="app" /></el-select></el-form-item>
        <el-form-item label="启用"><el-switch v-model="form.enabled" /></el-form-item>
      </el-form>
      <span slot="footer"><el-button @click="visible=false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></span>
    </el-dialog>
  </div>
</template>
<script>
import { repositoryCatalogApi } from '../api'
import { APPLICATIONS } from '../constants/applications'
const empty = () => ({ repositoryName: '', repositoryUrl: '', application: '', enabled: true })
export default {
  name: 'RepositoryCatalogView',
  data: () => ({ applications: APPLICATIONS, q: { keyword: '', application: '', pageNum: 1, pageSize: 20 }, rows: [], total: 0, loading: false, saving: false, visible: false, form: empty() }),
  created () { this.load() },
  methods: {
    async load () { this.loading = true; try { const result = await repositoryCatalogApi.page(this.q); this.rows = result.list; this.total = result.total } finally { this.loading = false } },
    edit (row) { this.form = row ? Object.assign(empty(), row) : empty(); this.visible = true },
    async save () { if (!/^[^/\s]+\/[^/\s]+$/.test(this.form.repositoryName)) return this.$message.warning('代码库名称必须使用xxx/xxx格式'); if (!this.form.repositoryUrl.trim()) return this.$message.warning('请填写代码库地址'); if (!this.form.application) return this.$message.warning('请选择应用'); this.saving = true; try { if (this.form.id) await repositoryCatalogApi.update(this.form.id, this.form); else await repositoryCatalogApi.create(this.form); this.visible = false; this.$message.success('代码库保存成功'); await this.load() } finally { this.saving = false } },
    async status (row, enabled) { await repositoryCatalogApi.status(row.id, enabled); row.enabled = enabled },
    async remove (row) { await this.$confirm('确认删除代码库 ' + row.repositoryName + '？'); await repositoryCatalogApi.remove(row.id); this.$message.success('删除成功'); await this.load() }
  }
}
</script>
