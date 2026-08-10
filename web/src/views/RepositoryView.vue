<template>
  <div class="page">
    <div class="toolbar"><el-input v-model="q.keyword" placeholder="扫描源、代码库或地址" @keyup.enter.native="load" /><el-select v-model="q.type" clearable placeholder="获取方式"><el-option label="Git拉取" value="GIT" /><el-option label="ZIP上传" value="UPLOAD" /><el-option label="MD HTTP" value="HTTP" /></el-select><el-button type="primary" @click="load">查询</el-button><el-button type="success" @click="edit()">新增扫描源</el-button></div>
    <el-table :data="rows" v-loading="loading">
      <el-table-column prop="repositoryName" label="扫描源" min-width="190" /><el-table-column prop="scanSourceType" label="内容" width="80" /><el-table-column label="获取方式" width="110"><template slot-scope="s">{{ typeName(s.row.sourceType) }}</template></el-table-column><el-table-column prop="application" label="应用" width="110" />
      <el-table-column label="来源信息" min-width="300" show-overflow-tooltip><template slot-scope="s"><span v-if="s.row.sourceType==='GIT'">{{ s.row.repositoryCode }} / {{ s.row.defaultBranch }}</span><span v-else-if="s.row.sourceType==='UPLOAD'">单项目ZIP：{{ s.row.originalFileName || '尚未上传' }}</span><span v-else>{{ mdTypeName(s.row.mdDocumentType) }} / {{ s.row.application }} / {{ s.row.versionNo }}</span></template></el-table-column>
      <el-table-column label="状态" width="90"><template slot-scope="s"><el-switch :value="s.row.enabled" @change="status(s.row,$event)" /></template></el-table-column><el-table-column label="操作" width="230"><template slot-scope="s"><el-button size="mini" @click="edit(s.row)">编辑</el-button><el-upload v-if="s.row.sourceType==='UPLOAD'" class="inline-upload" accept=".zip,application/zip" :show-file-list="false" :before-upload="validateZip" :http-request="o=>upload(s.row,o.file)"><el-button size="mini">替换ZIP</el-button></el-upload><el-button size="mini" type="danger" @click="remove(s.row)">删除</el-button></template></el-table-column>
    </el-table><el-pagination class="pager" layout="total,prev,pager,next" :total="total" @current-change="p=>{q.pageNum=p;load()}" />

    <el-dialog :title="form.id?'编辑扫描源':'新增扫描源'" :visible.sync="visible" width="680px">
      <el-form :model="form" label-width="120px">
        <el-form-item label="获取方式" required><el-radio-group v-model="form.sourceType" :disabled="Boolean(form.id)" @change="sourceTypeChanged"><el-radio label="GIT">Git拉取</el-radio><el-radio label="UPLOAD">ZIP上传</el-radio><el-radio label="HTTP">接口获取</el-radio></el-radio-group></el-form-item>
        <template v-if="form.sourceType==='GIT'">
          <el-form-item label="代码库" required><el-select v-model="form.repositoryCatalogIds" multiple filterable collapse-tags style="width:100%"><el-option v-for="repo in catalogs" :key="repo.id" :label="repo.repositoryName+'（'+repo.application+'）'" :value="repo.id" /></el-select></el-form-item>
          <el-form-item label="分支" required><el-input v-model.trim="form.defaultBranch" maxlength="128" placeholder="手工输入统一拉取分支，例如 feature/202608" /></el-form-item>
          <el-alert title="一次可选择多个有权访问的项目，系统使用公用只读账号按输入的同一分支分别拉取。" type="info" :closable="false" />
        </template>
        <template v-if="form.sourceType==='HTTP'">
          <el-form-item label="应用" required><el-select v-model="form.application" style="width:100%" :disabled="!isAdmin" @change="applicationChanged"><el-option v-for="app in applications" :key="app" :label="app" :value="app" /></el-select></el-form-item>
        </template>
        <template v-if="form.sourceType==='UPLOAD'">
          <el-form-item label="项目" required><el-select v-model="form.repositoryCatalogId" filterable style="width:100%"><el-option v-for="repo in catalogs" :key="repo.id" :label="repo.repositoryName+'（'+repo.application+'）'" :value="repo.id" /></el-select></el-form-item>
          <el-form-item label="ZIP文件" :required="!form.id"><el-upload action="#" accept=".zip,application/zip" :auto-upload="false" :limit="1" :file-list="zipFileList" :on-change="onZipChange" :on-remove="onZipRemove"><el-button size="small" type="primary">选择单项目ZIP</el-button></el-upload><div v-if="form.originalFileName" class="success">当前文件：{{ form.originalFileName }}</div><div class="form-tip">每次只能上传一个项目对应的一个ZIP包。</div></el-form-item>
        </template>
        <template v-if="form.sourceType==='HTTP'">
          <el-form-item label="文档类型" required><el-radio-group v-model="form.mdDocumentType" @change="mdTypeChanged"><el-radio label="OVERVIEW_DESIGN">概要设计.md</el-radio><el-radio label="DETAIL_DESIGN">详细设计.md</el-radio></el-radio-group></el-form-item>
          <el-form-item label="版本" required><el-select v-model="form.versionNo" style="width:100%" :loading="versionsLoading"><el-option v-for="version in versions" :key="version" :label="version" :value="version" /></el-select></el-form-item>
          <el-alert title="概要设计.md与详细设计.md分别调用独立配置的版本接口和文档接口，版本格式为YYYYMM。" type="info" :closable="false" />
        </template>
        <el-form-item label="说明"><el-input v-model="form.description" type="textarea" /></el-form-item><el-form-item label="启用"><el-switch v-model="form.enabled" /></el-form-item>
      </el-form><span slot="footer"><el-button @click="visible=false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></span>
    </el-dialog>
  </div>
</template>
<script>
import { repoApi, repositoryCatalogApi, userApi } from '../api'
import { APPLICATIONS } from '../constants/applications'
const empty = () => ({ repositoryName: '', application: '', repositoryCatalogId: null, repositoryCatalogIds: [], mdDocumentType: 'OVERVIEW_DESIGN', versionNo: '', scanSourceType: 'CODE', sourceType: 'GIT', defaultBranch: '', description: '', enabled: true })
export default {
  name: 'RepositoryView',
  data: () => ({ applications: APPLICATIONS, currentUser: {}, catalogs: [], versions: [], rows: [], total: 0, loading: false, saving: false, versionsLoading: false, visible: false, form: empty(), zipFile: null, zipFileList: [], q: { keyword: '', type: '', pageNum: 1, pageSize: 20 } }),
  computed: { isAdmin () { return this.currentUser.roleCode === 'ADMIN' } },
  async created () { this.currentUser = await userApi.get(1); await this.load() },
  methods: {
    typeName (type) { return ({ GIT: 'Git拉取', UPLOAD: 'ZIP上传', HTTP: '接口获取' })[type] || type },
    mdTypeName (type) { return ({ OVERVIEW_DESIGN: '概要设计.md', DETAIL_DESIGN: '详细设计.md' })[type] || type || '-' },
    async load () { this.loading = true; try { const result = await repoApi.page(this.q); this.rows = result.list; this.total = result.total } finally { this.loading = false } },
    async edit (row) { this.form = row ? Object.assign(empty(), row, { repositoryCatalogIds: row.repositoryCatalogIds || (row.repositoryCatalogId ? [row.repositoryCatalogId] : []) }) : empty(); if (!this.isAdmin && this.form.sourceType !== 'GIT') this.form.application = this.currentUser.application; this.zipFile = null; this.zipFileList = []; this.versions = this.form.versionNo ? [this.form.versionNo] : []; this.catalogs = await repositoryCatalogApi.mine(); this.visible = true; if (this.form.sourceType === 'HTTP' && this.form.application) await this.applicationChanged(this.form.application, true) },
    async sourceTypeChanged (type) { this.form = Object.assign(empty(), { sourceType: type, scanSourceType: type === 'HTTP' ? 'MD' : 'CODE', application: this.isAdmin ? '' : this.currentUser.application }); this.zipFile = null; this.zipFileList = []; this.versions = [] },
    async mdTypeChanged () { await this.applicationChanged(this.form.application, false) },
    async applicationChanged (application, preserve) { const selected = preserve ? this.form.versionNo : ''; this.form.versionNo = ''; this.versions = []; if (this.form.sourceType !== 'HTTP' || !this.form.mdDocumentType || !application) return; this.versionsLoading = true; try { this.versions = await repoApi.mdVersions(this.form.mdDocumentType, application); if (selected && this.versions.includes(selected)) this.form.versionNo = selected } finally { this.versionsLoading = false } },
    validateZip (file) { if (!file.name.toLowerCase().endsWith('.zip')) { this.$message.warning('只允许上传ZIP文件'); return false } if (file.size > 50 * 1024 * 1024) { this.$message.warning('ZIP文件不能超过50MB'); return false } return true },
    onZipChange (file) { if (!this.validateZip(file.raw)) { this.zipFile = null; this.zipFileList = []; return } this.zipFile = file.raw; this.zipFileList = [file] }, onZipRemove () { this.zipFile = null; this.zipFileList = [] },
    async save () { if (this.form.sourceType === 'GIT' && (!this.form.repositoryCatalogIds.length || !this.form.defaultBranch)) return this.$message.warning('请选择至少一个代码库并输入分支'); if (this.form.sourceType === 'UPLOAD' && !this.form.repositoryCatalogId) return this.$message.warning('请选择ZIP对应的单个项目'); if (this.form.sourceType === 'UPLOAD' && !this.form.id && !this.zipFile) return this.$message.warning('请选择单个项目的ZIP文件'); if (this.form.sourceType === 'HTTP' && (!this.form.mdDocumentType || !this.form.application || !/^\d{6}$/.test(this.form.versionNo))) return this.$message.warning('请选择文档类型、应用和YYYYMM格式版本'); const payload = Object.assign({}, this.form); this.saving = true; try { let id = this.form.id; if (id) await repoApi.update(id, payload); else id = await repoApi.create(payload); if (this.form.sourceType === 'UPLOAD' && this.zipFile) await repoApi.upload(id, this.zipFile); this.visible = false; this.$message.success('扫描源保存成功'); await this.load() } finally { this.saving = false } },
    async upload (row, file) { await repoApi.upload(row.id, file); this.$message.success('ZIP替换成功'); await this.load() }, async status (row, enabled) { await repoApi.status(row.id, enabled); row.enabled = enabled }, async remove (row) { await this.$confirm('确认删除该扫描源？'); await repoApi.remove(row.id); await this.load() }
  }
}
</script>
<style scoped>.inline-upload{display:inline-block;margin:0 10px}.el-alert{margin-bottom:16px}</style>
