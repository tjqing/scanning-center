<template>
  <div class="page">
    <div class="toolbar">
      <el-input v-model="q.keyword" placeholder="扫描源名称或地址" @keyup.enter.native="load" />
      <el-select v-model="q.type" clearable placeholder="来源类型">
        <el-option label="Git 仓库" value="GIT" />
        <el-option label="ZIP 资源" value="UPLOAD" />
        <el-option label="数据库文档" value="DATABASE" />
      </el-select>
      <el-button type="primary" @click="load">查询</el-button>
      <el-button type="success" @click="edit()">新增扫描源</el-button>
    </div>
    <el-table :data="rows">
      <el-table-column prop="repositoryName" label="扫描源名称" min-width="180" />
      <el-table-column prop="scanSourceType" label="扫描内容" width="100" />
      <el-table-column label="来源" width="120"><template slot-scope="s">{{ typeName(s.row.sourceType) }}</template></el-table-column>
      <el-table-column prop="application" label="所属应用" width="130" />
      <el-table-column label="资源信息" min-width="260" show-overflow-tooltip>
        <template slot-scope="s">
          <span v-if="s.row.sourceType === 'GIT'">{{ s.row.repositoryUrl }}</span>
          <span v-else-if="s.row.sourceType === 'DATABASE'">使用当前工程数据库</span>
          <span v-else-if="s.row.originalFileName" class="success">{{ s.row.originalFileName }}</span>
          <span v-else class="danger">尚未上传 ZIP</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90"><template slot-scope="s"><el-switch :value="s.row.enabled" @change="status(s.row, $event)" /></template></el-table-column>
      <el-table-column label="操作用户" width="150"><template slot-scope="s">{{ s.row.operatorUserId }} / {{ s.row.operatorUserName }}</template></el-table-column>
      <el-table-column label="操作" width="280"><template slot-scope="s">
        <el-button size="mini" @click="edit(s.row)">编辑</el-button>
        <el-upload v-if="s.row.sourceType === 'UPLOAD'" class="inline-upload" accept=".zip,application/zip" :show-file-list="false" :before-upload="validateZip" :http-request="o => upload(s.row, o.file)"><el-button size="mini">替换 ZIP</el-button></el-upload>
        <el-button size="mini" type="danger" @click="remove(s.row)">删除</el-button>
      </template></el-table-column>
    </el-table>
    <el-pagination class="pager" layout="total,prev,pager,next" :total="total" @current-change="p => { q.pageNum = p; load() }" />
    <el-dialog :title="form.id ? '编辑扫描源' : '新增扫描源'" :visible.sync="visible" width="720px">
      <el-form :model="form" label-width="120px">
        <el-form-item label="扫描源名称"><el-input v-model="form.repositoryName" /></el-form-item>
        <el-form-item label="扫描内容"><el-radio-group v-model="form.scanSourceType" :disabled="Boolean(form.id)" @change="scanContentChanged"><el-radio label="CODE">CODE</el-radio><el-radio label="MD">MD</el-radio></el-radio-group></el-form-item>
        <el-form-item v-if="form.scanSourceType === 'CODE'" label="获取方式"><el-radio-group v-model="form.sourceType" :disabled="Boolean(form.id)"><el-radio label="GIT">Git 仓库</el-radio><el-radio label="UPLOAD">ZIP 资源</el-radio></el-radio-group></el-form-item>
        <el-form-item label="所属应用"><el-input v-model="form.application" placeholder="例如 scan-center" /></el-form-item>
        <template v-if="form.sourceType === 'GIT'">
          <el-form-item label="代码库编码"><el-input v-model="form.repositoryCode" placeholder="全局唯一代码库编码" /></el-form-item>
          <el-form-item label="仓库地址"><el-input v-model="form.repositoryUrl" /></el-form-item>
          <el-form-item label="默认分支"><el-input v-model="form.defaultBranch" /></el-form-item>
          <el-alert title="连接测试、分支查询和拉取统一使用系统配置的Git公用只读账号。" type="info" :closable="false" />
        </template>
        <template v-else-if="form.sourceType === 'DATABASE'">
          <el-alert title="MD内容按所属应用和任务版本从数据库读取；查询必须使用 :application 和 :version 参数，单次最多读取10000条。" type="info" :closable="false" />
          <el-form-item label="文档查询 SQL"><el-input v-model="form.documentQuery" type="textarea" :rows="5" placeholder="SELECT title, content, file_type FROM design_document WHERE application = :application AND version_no = :version" /></el-form-item>
          <el-form-item label="名称字段"><el-input v-model="form.documentNameColumn" placeholder="title" /></el-form-item>
          <el-form-item label="内容字段"><el-input v-model="form.documentContentColumn" placeholder="content" /></el-form-item>
          <el-form-item label="类型字段"><el-input v-model="form.documentTypeColumn" placeholder="file_type（可选，默认 txt）" /></el-form-item>
        </template>
        <el-form-item v-else label="ZIP 文件" required><el-upload action="#" accept=".zip,application/zip" :auto-upload="false" :limit="1" :file-list="zipFileList" :on-change="onZipChange" :on-remove="onZipRemove"><el-button size="small" type="primary">选择 ZIP</el-button></el-upload><div v-if="form.originalFileName" class="success">当前文件：{{ form.originalFileName }}</div></el-form-item>
        <el-form-item label="扫描文件类型"><el-input v-model="form.fileTypes" placeholder="java,vue,md,pdf,docx,txt" /></el-form-item>
        <el-form-item label="排除目录"><el-input v-model="form.excludePatterns" placeholder="target,node_modules,.git" /></el-form-item>
        <el-form-item label="用途说明"><el-input v-model="form.description" type="textarea" /></el-form-item>
        <el-form-item label="启用"><el-switch v-model="form.enabled" /></el-form-item>
      </el-form>
      <span slot="footer"><el-button @click="visible = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></span>
    </el-dialog>
  </div>
</template>
<script>
import { repoApi } from '../api'
const empty = () => ({ repositoryName: '', repositoryCode: '', application: '', scanSourceType: 'CODE', sourceType: 'GIT', repositoryUrl: '', defaultBranch: 'main', documentQuery: '', documentNameColumn: 'title', documentContentColumn: 'content', documentTypeColumn: 'file_type', fileTypes: 'java,js,ts,vue,xml,yml,yaml,md,txt,sql,docx,pdf', excludePatterns: 'target,node_modules,.git', description: '', enabled: true })
export default { name: 'RepositoryView', data: () => ({ q: { keyword: '', type: '', pageNum: 1, pageSize: 20 }, rows: [], total: 0, visible: false, saving: false, form: empty(), zipFile: null, zipFileList: [] }), created () { this.load() }, methods: {
  typeName (type) { return ({ GIT: 'Git 仓库', UPLOAD: 'ZIP 资源', DATABASE: '数据库文档' })[type] || type },
  async load () { const r = await repoApi.page(this.q); this.rows = r.list; this.total = r.total },
  edit (row) { this.form = row ? Object.assign(empty(), row) : empty(); this.zipFile = null; this.zipFileList = []; this.visible = true },
  scanContentChanged (value) { this.form.sourceType = value === 'MD' ? 'DATABASE' : 'GIT'; this.form.repositoryCode = ''; this.form.repositoryUrl = ''; this.form.defaultBranch = value === 'MD' ? '' : 'main'; this.zipFile = null; this.zipFileList = [] },
  validateZip (file) { if (!file.name.toLowerCase().endsWith('.zip')) { this.$message.warning('只允许上传 ZIP 文件'); return false } if (file.size > 50 * 1024 * 1024) { this.$message.warning('ZIP 文件不能超过 50MB'); return false } return true },
  onZipChange (file) { if (!this.validateZip(file.raw)) { this.zipFile = null; this.zipFileList = []; return } this.zipFile = file.raw; this.zipFileList = [file] }, onZipRemove () { this.zipFile = null; this.zipFileList = [] },
  async save () { if (!this.form.repositoryName || !this.form.application) return this.$message.warning('请填写扫描源名称和所属应用'); if (!this.validCommaList(this.form.fileTypes, /^[A-Za-z0-9]+(?:[._+-][A-Za-z0-9]+)*$/)) return this.$message.warning('扫描文件类型必须使用英文逗号分隔，且不能包含空项或非法字符'); if (!this.validCommaList(this.form.excludePatterns, /^[^,，;；\s]+$/)) return this.$message.warning('排除目录必须使用英文逗号分隔，且不能包含空项或空格'); if (this.form.sourceType === 'UPLOAD' && !this.form.id && !this.zipFile) return this.$message.warning('请选择 ZIP 文件'); if (this.form.sourceType === 'GIT' && (!this.form.repositoryUrl || !this.form.repositoryCode)) return this.$message.warning('CODE Git扫描源必须填写代码库编码和仓库地址'); if (this.form.sourceType === 'DATABASE' && (!this.form.documentQuery || !/^\s*select\b/i.test(this.form.documentQuery) || this.form.documentQuery.includes(';') || !this.form.documentQuery.includes(':application') || !this.form.documentQuery.includes(':version'))) return this.$message.warning('MD文档查询必须是包含 :application 和 :version 参数的单条SELECT'); if (this.form.sourceType === 'DATABASE' && (!this.validColumn(this.form.documentNameColumn) || !this.validColumn(this.form.documentContentColumn) || (this.form.documentTypeColumn && !this.validColumn(this.form.documentTypeColumn)))) return this.$message.warning('文档字段必须是合法的数据库字段名'); this.saving = true; try { let id = this.form.id; if (id) await repoApi.update(id, this.form); else id = await repoApi.create(this.form); if (this.form.sourceType === 'UPLOAD' && this.zipFile) await repoApi.upload(id, this.zipFile); this.visible = false; this.$message.success('扫描源保存成功'); await this.load() } finally { this.saving = false } },
  validCommaList (value, pattern) { if (!value) return true; if (/[，；;]/.test(value) || value.startsWith(',') || value.endsWith(',') || value.includes(',,')) return false; return value.split(',').every(item => item.trim() && pattern.test(item.trim())) },
  validColumn (value) { return /^[A-Za-z_][A-Za-z0-9_]*$/.test(value || '') },
  async upload (row, file) { await repoApi.upload(row.id, file); this.$message.success('ZIP 上传成功'); await this.load() }, async status (row, enabled) { await repoApi.status(row.id, enabled); row.enabled = enabled }, async remove (row) { await this.$confirm('确认删除该扫描源？'); await repoApi.remove(row.id); await this.load() }
} }
</script>
<style scoped>.inline-upload { display: inline-block; margin: 0 10px; }.el-alert { margin-bottom: 16px; }</style>
