<template>
  <div class="page">
    <div class="toolbar">
      <el-input v-model="q.keyword" placeholder="扫描源、代码库或地址" @keyup.enter.native="load" />
      <el-select v-model="q.type" clearable placeholder="获取方式">
        <el-option label="Git拉取" value="GIT" /><el-option label="ZIP上传" value="UPLOAD" /><el-option label="MD HTTP" value="HTTP" />
      </el-select>
      <el-select v-model="q.application" clearable placeholder="应用">
        <el-option v-for="app in applications" :key="app" :label="app" :value="app" />
      </el-select>
      <el-select v-model="q.versionNo" clearable placeholder="版本">
        <el-option v-for="v in versionOptions" :key="v.versionNo" :label="v.label" :value="v.versionNo" />
      </el-select>
      <el-button type="primary" @click="load">查询</el-button>
      <el-button type="success" @click="edit()">新增扫描源</el-button>
    </div>
    <el-table :data="rows" v-loading="loading">
      <el-table-column prop="repositoryName" label="扫描源" min-width="190" />
      <el-table-column prop="scanSourceType" label="内容" width="80" />
      <el-table-column label="获取方式" width="110"><template slot-scope="s">{{ typeName(s.row.sourceType) }}</template></el-table-column>
      <el-table-column prop="application" label="应用" width="110" />
      <el-table-column label="版本" width="140"><template slot-scope="s">{{ versionLabel(s.row.versionNo) }}</template></el-table-column>
      <el-table-column label="来源信息" min-width="280" show-overflow-tooltip>
        <template slot-scope="s">
          <span v-if="s.row.sourceType==='GIT'">{{ s.row.repositoryCode }} / {{ s.row.defaultBranch }}</span>
          <span v-else-if="s.row.sourceType==='UPLOAD'">单项目ZIP：{{ s.row.originalFileName || '尚未上传' }}</span>
          <span v-else>{{ mdTypeName(s.row.mdDocumentType) }} / {{ s.row.application }} / {{ versionLabel(s.row.versionNo) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90"><template slot-scope="s"><el-switch :value="s.row.enabled" @change="status(s.row,$event)" /></template></el-table-column>
      <el-table-column label="操作" width="230">
        <template slot-scope="s">
          <el-button size="mini" @click="edit(s.row)">编辑</el-button>
          <el-upload v-if="s.row.sourceType==='UPLOAD'" class="inline-upload" accept=".zip,application/zip" :show-file-list="false" :before-upload="validateZip" :http-request="o=>upload(s.row,o.file)">
            <el-button size="mini">替换ZIP</el-button>
          </el-upload>
          <el-button size="mini" type="danger" @click="remove(s.row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination class="pager" layout="total,prev,pager,next" :total="total" @current-change="p=>{q.pageNum=p;load()}" />

    <el-dialog :title="form.id?'编辑扫描源':'新增扫描源'" :visible.sync="visible" width="680px">
      <el-form :model="form" label-width="120px">
        <el-form-item label="获取方式" required>
          <el-radio-group v-model="form.sourceType" :disabled="Boolean(form.id)" @change="sourceTypeChanged">
            <el-radio label="GIT">Git拉取</el-radio>
            <el-radio label="UPLOAD">ZIP上传</el-radio>
            <el-radio label="HTTP">接口获取</el-radio>
          </el-radio-group>
        </el-form-item>

        <template v-if="form.sourceType==='GIT'">
          <el-form-item label="代码库" required>
            <el-select v-model="form.repositoryCatalogIds" multiple filterable collapse-tags style="width:100%" @change="gitCatalogsChanged">
              <el-option v-for="repo in catalogs" :key="repo.id" :label="catalogOptionLabel(repo)" :value="repo.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="应用">
            <el-input :value="gitEchoApplication || '请先选择代码库'" disabled />
          </el-form-item>
          <el-form-item label="版本">
            <el-input :value="gitEchoVersionLabel || '请先选择代码库'" disabled />
          </el-form-item>
          <el-form-item label="分支" required>
            <el-input v-model.trim="form.defaultBranch" maxlength="128" placeholder="手工输入统一拉取分支，例如 feature/202608" />
          </el-form-item>
          <el-alert title="应用/版本取自代码库维护中已配置的值，选择代码库后自动回显；系统使用库内 SSH 私钥拉取同一分支。" type="info" :closable="false" />
        </template>

        <template v-if="form.sourceType==='UPLOAD'">
          <el-form-item label="应用" required>
            <el-select v-model="form.application" style="width:100%" :disabled="!isAdmin" @change="zipApplicationChanged">
              <el-option v-for="app in zipApplications" :key="app" :label="app" :value="app" />
            </el-select>
          </el-form-item>
          <el-form-item label="版本" required>
            <el-select v-model="form.versionNo" style="width:100%" :loading="versionsLoading" placeholder="请选择版本" clearable :disabled="!form.application">
              <el-option v-for="version in versions" :key="version.id" :label="version.label" :value="version.versionNo" />
            </el-select>
          </el-form-item>
          <el-form-item label="ZIP文件" :required="!form.id">
            <el-upload action="#" accept=".zip,application/zip" :auto-upload="false" :limit="1" :file-list="zipFileList" :on-change="onZipChange" :on-remove="onZipRemove">
              <el-button size="small" type="primary">选择单项目ZIP</el-button>
            </el-upload>
            <div v-if="form.originalFileName" class="success">当前文件：{{ form.originalFileName }}</div>
            <div class="form-tip">每次只能上传一个项目对应的一个ZIP包。</div>
          </el-form-item>
        </template>

        <template v-if="form.sourceType==='HTTP'">
          <el-form-item label="应用" required>
            <el-select v-model="form.application" style="width:100%" :disabled="!isAdmin" @change="v=>applicationChanged(v,false)">
              <el-option v-for="app in applications" :key="app" :label="app" :value="app" />
            </el-select>
          </el-form-item>
          <el-form-item label="版本" required>
            <el-select v-model="form.versionNo" style="width:100%" :loading="versionsLoading" placeholder="请选择版本" clearable :disabled="!form.application">
              <el-option v-for="version in versions" :key="version.id" :label="version.label" :value="version.versionNo" />
            </el-select>
          </el-form-item>
          <el-form-item label="文档类型" required>
            <el-radio-group v-model="form.mdDocumentType" @change="mdTypeChanged">
              <el-radio label="OVERVIEW_DESIGN">概要设计</el-radio>
              <el-radio label="DETAIL_DESIGN">详细设计.md</el-radio>
            </el-radio-group>
          </el-form-item>
          <template v-if="isOverview">
            <el-form-item label="文档" :required="!form.id">
              <el-upload action="#" accept=".docx,application/vnd.openxmlformats-officedocument.wordprocessingml.document" :auto-upload="false" :limit="1" :file-list="mdFileList" :on-change="onMdFileChange" :on-remove="onMdFileRemove">
                <el-button size="small" type="primary">选择线上文档.docx</el-button>
              </el-upload>
              <div v-if="form.originalFileName" class="success">当前文件：{{ form.originalFileName }}</div>
              <div class="form-tip">从技术管理平台下载概要设计线上文档docx后上传，每次只允许上传单个文档。</div>
            </el-form-item>
          </template>
          <el-alert v-else title="详细设计.md暂时没有可上传文档，版本为前端挡板数据（后续接真实接口）。" type="info" :closable="false" />
        </template>

        <el-form-item label="说明"><el-input v-model="form.description" type="textarea" /></el-form-item>
        <el-form-item label="启用"><el-switch v-model="form.enabled" /></el-form-item>
      </el-form>
      <span slot="footer">
        <el-button @click="visible=false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </span>
    </el-dialog>
  </div>
</template>
<script>
import { repoApi, repositoryCatalogApi } from '../api'
import { APPLICATIONS, CONCRETE_APPLICATIONS } from '../constants/applications'
import { loadMdVersions, MD_VERSION_MOCK } from '../constants/mdVersions'
import { getUser } from '../utils/auth'

const empty = () => ({
  repositoryName: '', application: '', versionNo: '', repositoryCatalogId: null, repositoryCatalogIds: [],
  mdDocumentType: 'OVERVIEW_DESIGN', scanSourceType: 'CODE', sourceType: 'GIT',
  defaultBranch: '', description: '', enabled: true
})

export default {
  name: 'RepositoryView',
  data: () => ({
    applications: APPLICATIONS,
    zipApplications: CONCRETE_APPLICATIONS,
    versionOptions: MD_VERSION_MOCK,
    currentUser: {},
    catalogs: [],
    versions: [],
    rows: [],
    total: 0,
    loading: false,
    saving: false,
    versionsLoading: false,
    visible: false,
    form: empty(),
    zipFile: null,
    zipFileList: [],
    mdFile: null,
    mdFileList: [],
    q: { keyword: '', type: '', application: '', versionNo: '', pageNum: 1, pageSize: 20 }
  }),
  computed: {
    isAdmin () { return this.currentUser.roleCode === 'ADMIN' },
    isOverview () { return this.form.sourceType === 'HTTP' && this.form.mdDocumentType === 'OVERVIEW_DESIGN' },
    selectedGitCatalogs () {
      const ids = this.form.repositoryCatalogIds || []
      return this.catalogs.filter(item => ids.indexOf(item.id) >= 0)
    },
    gitEchoApplication () {
      const apps = []
      this.selectedGitCatalogs.forEach(item => {
        if (item.application && apps.indexOf(item.application) < 0) apps.push(item.application)
      })
      if (!apps.length) return ''
      return apps.length === 1 ? apps[0] : apps.join('、') + '（多应用）'
    },
    gitEchoVersionLabel () {
      const versions = []
      this.selectedGitCatalogs.forEach(item => {
        const key = item.versionNo || ''
        if (key && versions.indexOf(key) < 0) versions.push(key)
      })
      if (!versions.length) return this.selectedGitCatalogs.length ? '（代码库未配置版本）' : ''
      if (versions.length === 1) return this.versionLabel(versions[0])
      return versions.map(v => this.versionLabel(v)).join('、') + '（多版本）'
    }
  },
  async created () {
    this.currentUser = getUser() || {}
    await this.load()
  },
  methods: {
    /** 将获取方式编码转为中文显示名 */
    typeName (type) { return ({ GIT: 'Git拉取', UPLOAD: 'ZIP上传', HTTP: '接口获取' })[type] || type },
    /** 将文档类型编码转为中文显示名 */
    mdTypeName (type) { return ({ OVERVIEW_DESIGN: '概要设计.md', DETAIL_DESIGN: '详细设计.md' })[type] || type || '-' },
    /** 根据版本号查找并返回版本标签名称，未配置时返回 '-' */
    versionLabel (versionNo) {
      if (!versionNo) return '-'
      const hit = MD_VERSION_MOCK.find(item => item.versionNo === versionNo)
      return hit ? hit.label : versionNo
    },
    /** 拼接代码库下拉选项的展示文案（名称 + 应用 / 版本） */
    catalogOptionLabel (repo) {
      const ver = repo.versionNo ? this.versionLabel(repo.versionNo) : '未配版本'
      return repo.repositoryName + '（' + (repo.application || '-') + ' / ' + ver + '）'
    },
    /** 分页加载扫描源列表 */
    async load () {
      this.loading = true
      try {
        const result = await repoApi.page(this.q)
        this.rows = result.list
        this.total = result.total
      } finally { this.loading = false }
    },
    /** 打开新增/编辑弹窗：初始化表单并加载关联数据 */
    async edit (row) {
      this.form = row
        ? Object.assign(empty(), row, {
          repositoryCatalogIds: row.repositoryCatalogIds || (row.repositoryCatalogId ? [row.repositoryCatalogId] : [])
        })
        : empty()
      // 非管理员且非 GIT 源时，应用强制为当前用户的应用
      if (!this.isAdmin && this.form.sourceType !== 'GIT') this.form.application = this.currentUser.application
      // 清空已选文件与版本
      this.zipFile = null
      this.zipFileList = []
      this.mdFile = null
      this.mdFileList = []
      this.versions = []
      // 加载当前用户的代码库
      this.catalogs = await repositoryCatalogApi.mine()
      this.visible = true
      // 根据获取方式加载对应数据
      if (this.form.sourceType === 'HTTP') await this.applicationChanged(this.form.application, true)
      if (this.form.sourceType === 'UPLOAD' && this.form.application) await this.loadVersions(this.form.application, true)
      if (this.form.sourceType === 'GIT') this.syncGitEchoToForm()
    },
    /** 获取方式切换：重置表单并加载对应方式所需的版本数据 */
    async sourceTypeChanged (type) {
      this.form = Object.assign(empty(), {
        sourceType: type,
        scanSourceType: type === 'HTTP' ? 'MD' : 'CODE',
        application: this.isAdmin ? '' : this.currentUser.application
      })
      this.zipFile = null
      this.zipFileList = []
      this.mdFile = null
      this.mdFileList = []
      this.versions = []
      if ((type === 'HTTP' || type === 'UPLOAD') && this.form.application) {
        await this.loadVersions(this.form.application, false)
      }
    },
    /** 代码库多选变化时，同步回显应用与版本 */
    gitCatalogsChanged () {
      this.syncGitEchoToForm()
    },
    /** 根据选中的代码库，将应用/版本回填到表单 */
    syncGitEchoToForm () {
      const selected = this.selectedGitCatalogs
      if (!selected.length) {
        this.form.application = ''
        this.form.versionNo = ''
        return
      }
      const apps = []
      const versions = []
      selected.forEach(item => {
        if (item.application && apps.indexOf(item.application) < 0) apps.push(item.application)
        if (item.versionNo && versions.indexOf(item.versionNo) < 0) versions.push(item.versionNo)
      })
      // 单应用回填该应用，多应用回填 ALL；版本多选时不回填
      this.form.application = apps.length === 1 ? apps[0] : (apps.length ? 'ALL' : '')
      this.form.versionNo = versions.length === 1 ? versions[0] : ''
    },
    /** ZIP 上传方式下切换应用时，重新加载版本列表 */
    async zipApplicationChanged (application) {
      await this.loadVersions(application, false)
    },
    /** 文档类型切换：清空版本并重新加载 */
    async mdTypeChanged () {
      this.form.versionNo = ''
      if (this.isOverview) {
        this.mdFile = null
        this.mdFileList = []
      }
      await this.applicationChanged(this.form.application, false)
    },
    /** 应用变化：重新加载版本列表 */
    async applicationChanged (application, preserve) {
      await this.loadVersions(application, preserve)
    },
    /** 按应用加载 MD 版本列表；preserve 为 true 时尽量保留当前已选版本 */
    async loadVersions (application, preserve) {
      const selected = preserve ? this.form.versionNo : ''
      this.form.versionNo = ''
      this.versions = []
      if (!application) return
      this.versionsLoading = true
      try {
        this.versions = await loadMdVersions(this.form.mdDocumentType || null, application)
        if (selected && this.versions.some(item => item.versionNo === selected)) this.form.versionNo = selected
      } finally { this.versionsLoading = false }
    },
    /** 校验概要设计文档文件（必须为 docx 且不超过 50MB） */
    validateMdFile (file) {
      if (file.name.toLowerCase().lastIndexOf('.docx') !== file.name.length - 5) {
        this.$message.warning('概要设计文档只允许上传docx文件')
        return false
      }
      if (file.size > 50 * 1024 * 1024) {
        this.$message.warning('文档不能超过50MB')
        return false
      }
      return true
    },
    /** 文档选择变化：通过校验则暂存文件 */
    onMdFileChange (file) {
      if (!this.validateMdFile(file.raw)) {
        this.mdFile = null
        this.mdFileList = []
        return
      }
      this.mdFile = file.raw
      this.mdFileList = [file]
    },
    /** 移除已选文档 */
    onMdFileRemove () { this.mdFile = null; this.mdFileList = [] },
    /** 校验 ZIP 文件（必须为 .zip 且不超过 50MB） */
    validateZip (file) {
      if (!file.name.toLowerCase().endsWith('.zip')) {
        this.$message.warning('只允许上传ZIP文件')
        return false
      }
      if (file.size > 50 * 1024 * 1024) {
        this.$message.warning('ZIP文件不能超过50MB')
        return false
      }
      return true
    },
    /** ZIP 选择变化：通过校验则暂存文件 */
    onZipChange (file) {
      if (!this.validateZip(file.raw)) {
        this.zipFile = null
        this.zipFileList = []
        return
      }
      this.zipFile = file.raw
      this.zipFileList = [file]
    },
    /** 移除已选 ZIP 文件 */
    onZipRemove () { this.zipFile = null; this.zipFileList = [] },
    /** 校验并保存扫描源（新增或更新），UPLOAD 方式会附带上传 ZIP 文件 */
    async save () {
      // GIT 方式校验：必须选择代码库并填写分支
      if (this.form.sourceType === 'GIT') {
        if (!this.form.repositoryCatalogIds.length || !this.form.defaultBranch) {
          return this.$message.warning('请选择至少一个代码库并输入分支')
        }
        if (this.selectedGitCatalogs.some(item => !item.versionNo)) {
          return this.$message.warning('所选代码库存在未配置版本的记录，请先在代码库维护中补全版本')
        }
        this.syncGitEchoToForm()
      }
      // UPLOAD 方式校验：应用、版本及 ZIP 文件
      if (this.form.sourceType === 'UPLOAD') {
        if (!this.form.application) return this.$message.warning('请选择应用')
        if (!/^\d{6}$/.test(this.form.versionNo || '')) return this.$message.warning('请选择版本')
        if (!this.form.id && !this.zipFile) return this.$message.warning('请选择单个项目的ZIP文件')
        this.form.repositoryCatalogId = null
      }
      // HTTP 方式校验：文档类型、应用、版本及文档文件
      if (this.form.sourceType === 'HTTP') {
        if (!this.form.mdDocumentType || !this.form.application || !/^\d{6}$/.test(this.form.versionNo)) {
          return this.$message.warning('请选择文档类型、应用并填写YYYYMM格式版本')
        }
        if (this.isOverview && !this.form.id && !this.mdFile) {
          return this.$message.warning('概要设计请上传单个线上文档docx')
        }
      }
      const payload = Object.assign({}, this.form)
      this.saving = true
      try {
        let id = this.form.id
        if (id) await repoApi.update(id, payload)
        else id = await repoApi.create(payload)
        // 新增 UPLOAD 扫描源时上传 ZIP 文件
        if (this.form.sourceType === 'UPLOAD' && this.zipFile) await repoApi.upload(id, this.zipFile)
        this.visible = false
        this.$message.success('扫描源保存成功')
        await this.load()
      } finally { this.saving = false }
    },
    /** 表格内替换已有扫描源的 ZIP 文件并刷新 */
    async upload (row, file) {
      await repoApi.upload(row.id, file)
      this.$message.success('ZIP替换成功')
      await this.load()
    },
    /** 启用 / 停用扫描源 */
    async status (row, enabled) {
      await repoApi.status(row.id, enabled)
      row.enabled = enabled
    },
    /** 删除扫描源：二次确认后删除并刷新 */
    async remove (row) {
      await this.$confirm('确认删除该扫描源？')
      await repoApi.remove(row.id)
      await this.load()
    }
  }
}
</script>
<style scoped>
.inline-upload { display: inline-block; margin: 0 10px; }
.el-alert { margin-bottom: 16px; }
.form-tip { color: #909399; font-size: 12px; margin-top: 4px; }
</style>
