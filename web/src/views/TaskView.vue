<template>
  <div class="page">
    <div class="toolbar"><el-input v-model="q.keyword" placeholder="任务名称或编号" @keyup.enter.native="load"/><el-select v-model="q.status" clearable placeholder="任务状态"><el-option v-for="v in states" :key="v" :value="v" :label="v"/></el-select><el-button type="primary" @click="load">查询</el-button><el-button type="success" @click="open()">新建任务</el-button></div>
    <el-table :data="rows" v-loading="loading"><el-table-column prop="taskNo" label="任务编号" width="180"/><el-table-column prop="taskName" label="任务名称"/><el-table-column prop="taskType" label="规则类型" width="90"/><el-table-column prop="scanSourceType" label="扫描内容" width="90"/><el-table-column label="快照/清单" width="150"><template slot-scope="s"><div>V{{s.row.snapshotVersion||'-'}}</div><small :class="s.row.manifestStatus==='READY'?'success':'danger'">{{s.row.manifestStatus||'-'}} / {{s.row.manifestFileCount||0}} 文件</small></template></el-table-column><el-table-column label="扫描源" min-width="160"><template slot-scope="s"><div>{{s.row.repositoryName}}</div><small v-if="s.row.repositoryFileName" class="success">ZIP：{{s.row.repositoryFileName}}</small></template></el-table-column><el-table-column prop="status" label="状态" width="140"/><el-table-column label="进度" width="150"><template slot-scope="s"><el-progress :percentage="percent(s.row)"/></template></el-table-column><el-table-column prop="issueCount" label="问题数" width="80"/><el-table-column label="操作" width="470"><template slot-scope="s"><el-button size="mini" @click="detail(s.row)">详情</el-button><el-button size="mini" @click="preview(s.row)">清单</el-button><el-button size="mini" v-if="canEdit(s.row)" @click="open(s.row)">编辑</el-button><el-button size="mini" v-if="canEdit(s.row)" @click="copy(s.row)">复制</el-button><el-button size="mini" type="primary" v-if="canRun(s.row)" @click="run(s.row)">启动</el-button><el-button size="mini" type="warning" v-if="['QUEUED','RUNNING'].includes(s.row.status)" @click="stop(s.row)">停止</el-button><el-button size="mini" type="success" v-if="s.row.status==='PAUSED'" @click="resume(s.row)">继续</el-button><el-button size="mini" type="danger" v-if="s.row.status==='PAUSED'" @click="abort(s.row)">放弃</el-button><el-button size="mini" type="danger" v-if="canEdit(s.row)" @click="remove(s.row)">删除</el-button></template></el-table-column></el-table>
    <el-pagination class="pager" layout="total,prev,pager,next" :total="total" @current-change="p=>{q.pageNum=p;load()}"/>

    <el-dialog :title="form.id?'编辑扫描任务（保存后生成新快照）':'新建扫描任务'" :visible.sync="visible" width="760px"><el-alert title="任务保存后将固化规则、扫描源参数和内置提示词，并生成不可变扫描清单" type="info" :closable="false" style="margin-bottom:16px"/><el-form label-width="120px"><el-form-item label="任务名称" required><el-input v-model="form.taskName"/></el-form-item><el-form-item label="扫描源" required><el-select v-model="form.repositoryId" filterable @change="sourceChanged"><el-option v-for="r in repos" :key="r.id" :value="r.id" :label="repositoryLabel(r)"/></el-select></el-form-item><el-form-item v-if="selectedScanSourceType==='MD'" label="MD应用/版本"><span>{{selectedRepository.application}} / {{selectedRepository.versionNo}}</span></el-form-item><el-form-item label="扫描规则" required><el-select v-model="form.ruleIds" multiple filterable><el-option v-for="r in availableRules" :key="r.id" :value="r.id" :label="r.ruleName+'（'+r.ruleType+'）'"/></el-select></el-form-item><el-form-item label="扫描路径"><el-input v-model="form.scanPathsInput" placeholder="/ 或 src,docs；使用英文逗号分隔"/></el-form-item><el-form-item label="文件类型"><el-input v-model="form.fileTypesInput" placeholder="java,js,vue,md,docx,pdf"/></el-form-item><el-form-item label="排除路径"><el-input v-model="form.excludePathsInput" placeholder=".git,node_modules,target"/></el-form-item><el-form-item label="任务说明"><el-input type="textarea" v-model="form.description"/></el-form-item><el-form-item v-if="!form.id" label="生成后启动"><el-switch v-model="form.executeImmediately"/></el-form-item></el-form><span slot="footer"><el-button @click="visible=false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存并生成清单</el-button></span></el-dialog>

    <el-dialog title="任务详情与快照" :visible.sync="detailVisible" width="780px"><div v-if="selected" class="detail-grid"><div><label>任务编号</label>{{selected.taskNo}}</div><div><label>任务类型</label>{{selected.taskType}}</div><div><label>状态</label>{{selected.status}}</div><div><label>当前快照</label>V{{selected.snapshotVersion||'-'}}</div><div><label>清单状态</label>{{selected.manifestStatus}}</div><div><label>清单文件</label>{{selected.manifestFileCount||0}}</div><div><label>扫描源</label>{{selected.repositoryName}}</div><div><label>开始时间</label>{{selected.startTime||'-'}}</div><div><label>完成文件</label>{{selected.completedFiles}} / {{selected.totalFiles}}</div><div><label>成功/失败</label>{{selected.successFiles}} / {{selected.failedFiles}}</div><div><label>问题数</label>{{selected.issueCount}}</div><div><label>错误信息</label>{{selected.errorMessage||'-'}}</div></div><el-divider>快照历史</el-divider><el-table :data="snapshotRows" size="mini"><el-table-column prop="snapshotVersion" label="版本" width="80"/><el-table-column prop="taskType" label="类型" width="90"/><el-table-column prop="snapshotHash" label="快照摘要" show-overflow-tooltip/><el-table-column prop="createdByUserName" label="保存人" width="120"/><el-table-column prop="createTime" label="保存时间" width="170"/></el-table></el-dialog>

    <el-dialog title="扫描清单预览" :visible.sync="manifestVisible" width="900px"><div v-if="manifest" class="manifest-summary"><el-descriptions :column="3" border><el-descriptions-item label="清单版本">V{{manifest.manifestVersion}}</el-descriptions-item><el-descriptions-item label="状态">{{manifest.status}}</el-descriptions-item><el-descriptions-item label="文件数">{{manifest.fileCount}}</el-descriptions-item><el-descriptions-item label="排除数量">{{manifest.excludedCount}}</el-descriptions-item><el-descriptions-item label="总大小">{{formatSize(manifest.totalSize)}}</el-descriptions-item><el-descriptions-item label="生成时间">{{manifest.finishTime||'-'}}</el-descriptions-item><el-descriptions-item label="清单摘要" :span="3">{{manifest.manifestHash||'-'}}</el-descriptions-item></el-descriptions><div class="toolbar manifest-filter"><el-input v-model="manifestQuery.keyword" clearable placeholder="文件相对路径" @keyup.enter.native="loadManifestFiles"/><el-input v-model="manifestQuery.fileType" clearable placeholder="文件类型" @keyup.enter.native="loadManifestFiles"/><el-button type="primary" @click="loadManifestFiles">筛选</el-button><el-button @click="regenerate">重新生成</el-button></div><el-table :data="manifestFiles" height="360"><el-table-column type="index" width="60"/><el-table-column prop="relativePath" label="相对路径" min-width="380" show-overflow-tooltip/><el-table-column prop="fileType" label="类型" width="90"/><el-table-column label="大小" width="110"><template slot-scope="s">{{formatSize(s.row.fileSize)}}</template></el-table-column><el-table-column prop="applicableRuleCount" label="适用规则" width="90"/><el-table-column prop="contentHash" label="内容摘要" show-overflow-tooltip/></el-table><el-pagination class="pager" layout="total,prev,pager,next" :total="manifestTotal" :page-size="manifestQuery.pageSize" @current-change="p=>{manifestQuery.pageNum=p;loadManifestFiles()}"/></div></el-dialog>
  </div>
</template>
<script>
import { taskApi, repoApi, ruleApi } from '../api'

const empty = () => ({ taskName: '', description: '', repositoryId: null, ruleIds: [], scanPathsInput: '/', fileTypesInput: 'java,js,ts,vue,xml,yml,yaml,json,md,txt,sql,docx,pdf', excludePathsInput: '.git,node_modules,target', executeImmediately: false })
const split = value => (value || '').split(',').map(item => item.trim()).filter(Boolean)

export default {
  name: 'TaskView',
  data: () => ({
    q: { keyword: '', status: '', pageNum: 1, pageSize: 20 },
    states: ['DRAFT', 'READY', 'QUEUED', 'RUNNING', 'STOP_REQUESTED', 'PAUSED', 'SUCCESS', 'PARTIAL_SUCCESS', 'FAILED', 'ABORTED'],
    rows: [], total: 0, loading: false, visible: false, saving: false,
    detailVisible: false, manifestVisible: false, selected: null, snapshotRows: [],
    repos: [], rules: [], form: empty(), manifest: null, manifestTask: null,
    manifestFiles: [], manifestTotal: 0,
    manifestQuery: { keyword: '', fileType: '', pageNum: 1, pageSize: 50 }, timer: null
  }),
  computed: {
    selectedRepository () { return this.repos.find(item => item.id === this.form.repositoryId) || null },
    selectedScanSourceType () { return this.selectedRepository ? this.selectedRepository.scanSourceType : '' },
    availableRules () { return this.selectedScanSourceType === 'MD' ? this.rules.filter(item => item.ruleType === 'AI') : this.rules },
    selectedRuleType () { const values = this.rules.filter(item => this.form.ruleIds.includes(item.id)).map(item => item.ruleType); return values.length ? values[0] : '' }
  },
  created () { this.load(); this.timer = setInterval(this.load, 4000) },
  beforeDestroy () { clearInterval(this.timer) },
  methods: {
    async load () { this.loading = true; try { const result = await taskApi.page(this.q); this.rows = result.list; this.total = result.total } finally { this.loading = false } },
    percent (row) { return row.totalFiles ? Math.round((row.completedFiles || 0) * 100 / row.totalFiles) : 0 },
    canEdit (row) { return !['QUEUED', 'RUNNING', 'STOP_REQUESTED', 'PAUSED'].includes(row.status) },
    canRun (row) { return row.manifestStatus === 'READY' && !['QUEUED', 'RUNNING', 'STOP_REQUESTED', 'PAUSED'].includes(row.status) },
    repositoryLabel (row) { const app = row.application ? ' / ' + row.application : ''; const zip = row.sourceType === 'UPLOAD' ? ` / ZIP：${row.originalFileName || '未上传'}` : ''; return `[${row.scanSourceType}] ${row.repositoryName}${app}${zip}` },
    sourceChanged () { this.form.ruleIds = [] },
    async options () { const [repoResult, ruleResult] = await Promise.all([repoApi.page({ enabled: true, pageSize: 100 }), ruleApi.page({ enabled: true, pageSize: 100 })]); this.repos = repoResult.list.filter(item => item.sourceType !== 'UPLOAD' || Boolean(item.storageKey)); this.rules = ruleResult.list },
    async open (row) {
      await this.options()
      if (row) {
        const detail = await taskApi.get(row.id); let scope = {}
        try { scope = JSON.parse(detail.scopeJson || '{}') } catch (e) { scope = {} }
        this.form = Object.assign(empty(), detail, { ruleIds: [], scanPathsInput: (scope.scanPaths || ['.']).map(item => item === '.' ? '/' : item).join(','), fileTypesInput: (scope.fileTypes || []).join(','), excludePathsInput: (scope.excludePaths || []).join(',') })
        this.$message.info('编辑保存会生成新快照，请重新选择同类型规则')
      } else this.form = empty()
      this.visible = true
    },
    async save () {
      if (!this.form.taskName || !this.form.repositoryId || !this.form.ruleIds.length) return this.$message.warning('请填写任务名称、扫描源和规则')
      const selected = this.rules.filter(item => this.form.ruleIds.includes(item.id))
      if (new Set(selected.map(item => item.ruleType)).size !== 1) return this.$message.warning('一个任务只能选择同类型规则')
      if (this.selectedScanSourceType === 'MD' && this.selectedRuleType !== 'AI') return this.$message.warning('MD扫描源只能选择AI规则')
      const payload = Object.assign({}, this.form, { scanPaths: split(this.form.scanPathsInput).map(item => item === '/' ? '.' : item), fileTypes: split(this.form.fileTypesInput), excludePaths: split(this.form.excludePathsInput) })
      this.saving = true
      try { this.form.id ? await taskApi.update(this.form.id, payload) : await taskApi.create(payload); this.visible = false; this.$message.success('任务快照及扫描清单已生成'); this.load() } finally { this.saving = false }
    },
    async copy (row) { await taskApi.copy(row.id); this.$message.success('任务已复制并生成独立快照'); this.load() },
    async run (row) { await taskApi.run(row.id); this.load() },
    async stop (row) { await taskApi.stop(row.id); this.load() },
    async resume (row) { await taskApi.resume(row.id); this.load() },
    async abort (row) { await this.$confirm('放弃后该运行不能继续，已产生结果仍保留，确认放弃？'); await taskApi.abort(row.id); this.load() },
    async remove (row) { await this.$confirm('确认删除任务？历史快照和结果仍保留。'); await taskApi.remove(row.id); this.load() },
    async detail (row) { this.selected = await taskApi.get(row.id); this.snapshotRows = await taskApi.snapshots(row.id); this.detailVisible = true },
    async preview (row) { this.manifestTask = row; this.manifest = await taskApi.manifest(row.id); this.manifestQuery = { keyword: '', fileType: '', pageNum: 1, pageSize: 50 }; await this.loadManifestFiles(); this.manifestVisible = true },
    async loadManifestFiles () { if (!this.manifest) return; const result = await taskApi.manifestFiles(this.manifest.id, this.manifestQuery); this.manifestFiles = result.list; this.manifestTotal = result.total },
    async regenerate () { await taskApi.regenerateManifest(this.manifestTask.id); this.$message.success('扫描清单已重新生成'); this.manifest = await taskApi.manifest(this.manifestTask.id); this.loadManifestFiles(); this.load() },
    formatSize (value) { const size = Number(value || 0); if (size < 1024) return size + ' B'; if (size < 1024 * 1024) return (size / 1024).toFixed(1) + ' KB'; return (size / 1024 / 1024).toFixed(1) + ' MB' }
  }
}
</script>
<style scoped>.manifest-filter{margin-top:16px}.manifest-summary .el-descriptions{margin-bottom:8px}</style>
