<template>
  <div class="page">
    <el-tabs v-model="activeTab">
      <el-tab-pane label="扫描任务" name="tasks">
    <div class="toolbar"><el-input v-model="q.keyword" placeholder="任务名称或编号" @keyup.enter.native="load"/><el-select v-model="q.status" clearable placeholder="任务状态"><el-option v-for="v in states" :key="v" :value="v" :label="v"/></el-select><el-select v-model="q.application" clearable placeholder="应用"><el-option v-for="app in applications" :key="app" :label="app" :value="app"/></el-select><el-select v-model="q.versionNo" clearable placeholder="版本"><el-option v-for="v in versionOptions" :key="v.versionNo" :label="v.label" :value="v.versionNo"/></el-select><el-button type="primary" @click="load">查询</el-button><el-button @click="resetQuery">重置</el-button><el-button type="success" @click="open()">新建任务</el-button></div>
    <el-table :data="rows" v-loading="loading">
      <el-table-column prop="taskNo" label="任务编号" width="180"/>
      <el-table-column prop="taskName" label="任务名称" min-width="140"/>
      <el-table-column prop="application" label="应用" width="100"/>
      <el-table-column label="版本" width="140">
        <template slot-scope="s">{{ versionLabel(s.row.versionNo) }}</template>
      </el-table-column>
      <el-table-column prop="taskType" label="规则类型" width="90"/>
      <el-table-column prop="scanSourceType" label="扫描内容" width="90"/>
      <el-table-column label="快照/清单" width="150"><template slot-scope="s"><div>V{{s.row.snapshotVersion||'-'}}</div><small :class="s.row.manifestStatus==='READY'?'success':'danger'">{{s.row.manifestStatus||'-'}} / {{s.row.manifestFileCount||0}} 文件</small></template></el-table-column>
      <el-table-column label="扫描源" min-width="160"><template slot-scope="s"><div>{{s.row.repositoryName}}</div><small v-if="s.row.repositoryFileName" class="success">ZIP：{{s.row.repositoryFileName}}</small></template></el-table-column>
      <el-table-column label="定时发起" width="160"><template slot-scope="s"><span v-if="s.row.scheduleType==='ONCE'">{{s.row.scheduleTime||'-'}}</span><span v-else class="muted">-</span></template></el-table-column>
      <el-table-column prop="status" label="状态" width="140"/>
      <el-table-column label="进度" width="150"><template slot-scope="s"><el-progress :percentage="percent(s.row)"/></template></el-table-column>
      <el-table-column prop="issueCount" label="问题数" width="80"/>
      <el-table-column label="操作" width="470"><template slot-scope="s"><el-button size="mini" @click="detail(s.row)">详情</el-button><el-button size="mini" @click="preview(s.row)">清单</el-button><el-button size="mini" v-if="canEdit(s.row)" @click="open(s.row)">编辑</el-button><el-button size="mini" v-if="canEdit(s.row)" @click="copy(s.row)">复制</el-button><el-button size="mini" type="primary" v-if="canRun(s.row)" @click="run(s.row)">启动</el-button><el-button size="mini" type="warning" v-if="['QUEUED','RUNNING'].includes(s.row.status)" @click="stop(s.row)">停止</el-button><el-button size="mini" type="success" v-if="s.row.status==='PAUSED'" @click="resume(s.row)">继续</el-button><el-button size="mini" type="danger" v-if="s.row.status==='PAUSED'" @click="abort(s.row)">放弃</el-button><el-button size="mini" type="danger" v-if="canEdit(s.row)" @click="remove(s.row)">删除</el-button></template></el-table-column>
    </el-table>
    <el-pagination class="pager" layout="total,prev,pager,next" :total="total" @current-change="p=>{q.pageNum=p;load()}"/>
      </el-tab-pane>
      <el-tab-pane label="扫描结果" name="results">
        <div class="toolbar"><el-input v-model="resultQuery.keyword" clearable placeholder="任务名称/编号/运行号" @keyup.enter.native="searchResults" @clear="searchResults"/><el-select v-model="resultQuery.application" clearable placeholder="应用"><el-option v-for="app in applications" :key="app" :label="app" :value="app"/></el-select><el-select v-model="resultQuery.versionNo" clearable placeholder="版本"><el-option v-for="v in versionOptions" :key="v.versionNo" :label="v.label" :value="v.versionNo"/></el-select><el-button type="primary" @click="searchResults">查询</el-button><el-button @click="resetResultQuery">重置</el-button><span class="tip">不按问题数过滤；问题数为 0 的结果也会显示</span></div>
        <el-table :data="resultRows" v-loading="resultLoading" empty-text="暂无扫描结果（清空关键字后再查）"><el-table-column prop="taskNo" label="任务编号" width="160"/><el-table-column prop="taskName" label="任务名称" min-width="140"/><el-table-column prop="application" label="应用" width="100"/><el-table-column label="版本" width="140"><template slot-scope="s">{{ versionLabel(s.row.versionNo) }}</template></el-table-column><el-table-column prop="runNo" label="运行号" width="160" show-overflow-tooltip/><el-table-column prop="runStatus" label="运行状态" width="120"/><el-table-column prop="repositoryName" label="扫描源" min-width="120"/><el-table-column prop="scannedFiles" label="文件数" width="80"/><el-table-column prop="issueCount" label="问题数" width="80"/><el-table-column prop="highCount" label="高风险" width="80"/><el-table-column prop="mediumCount" label="中风险" width="80"/><el-table-column prop="createTime" label="创建时间" width="170"/><el-table-column label="操作" width="170"><template slot-scope="s"><el-button size="mini" @click="resultDetail(s.row)">查看</el-button><el-button size="mini" @click="$router.push('/issues?resultId='+s.row.id)">结果明细</el-button></template></el-table-column></el-table>
        <el-pagination class="pager" layout="total,prev,pager,next" :total="resultTotal" @current-change="p=>{resultQuery.pageNum=p;loadResults()}"/>
      </el-tab-pane>
    </el-tabs>

    <el-dialog :title="form.id?'编辑扫描任务（保存后生成新快照）':'新建扫描任务'" :visible.sync="visible" width="760px">
      <el-alert title="任务保存后将固化规则、扫描源参数和内置提示词，并生成不可变扫描清单" type="info" :closable="false" style="margin-bottom:16px"/>
      <el-form label-width="120px">
        <el-form-item label="任务名称" required><el-input v-model="form.taskName"/></el-form-item>
        <el-form-item label="扫描源" required>
          <el-select v-model="form.repositoryId" filterable style="width:100%" @change="sourceChanged">
            <el-option v-for="r in repos" :key="r.id" :value="r.id" :label="repositoryLabel(r)"/>
          </el-select>
        </el-form-item>
        <el-form-item label="应用">
          <el-input :value="echoApplication" disabled placeholder="请先选择扫描源"/>
        </el-form-item>
        <el-form-item label="版本">
          <el-input :value="echoVersionLabel" disabled placeholder="请先选择扫描源"/>
        </el-form-item>
        <el-form-item label="扫描规则" required>
          <el-select v-model="form.ruleIds" multiple filterable style="width:100%">
            <el-option v-for="r in availableRules" :key="r.id" :value="r.id" :label="r.ruleName+'（'+r.ruleType+'）'"/>
          </el-select>
        </el-form-item>
        <el-form-item label="扫描路径"><el-input v-model="form.scanPathsInput" placeholder="/ 或 src,docs；使用英文逗号分隔"/></el-form-item>
        <el-form-item label="作用域预设">
          <div class="scope-tag-box">
            <el-tag v-for="p in scopePresets" :key="p.key" :type="scopePresetKeys.indexOf(p.key) >= 0 ? p.tagType : 'info'" :closable="scopePresetKeys.indexOf(p.key) >= 0" :class="['scope-tag', scopePresetKeys.indexOf(p.key) >= 0 ? 'is-on' : 'is-off']" @click.native="toggleScopePreset(p.key)" @close="removeScopePreset(p.key)">{{ p.shortName }}</el-tag>
          </div>
          <div class="tip">点击选中，点叉取消；可多选并集合并，仍可手工改</div>
        </el-form-item>
        <el-form-item label="文件类型"><el-input v-model="form.fileTypesInput" placeholder="java,js,vue,md,docx,pdf"/></el-form-item>
        <el-form-item label="排除路径"><el-input v-model="form.excludePathsInput" placeholder=".git,node_modules,target"/></el-form-item>
        <el-form-item label="任务说明"><el-input type="textarea" v-model="form.description"/></el-form-item>
        <el-form-item label="定时发起"><el-switch v-model="scheduleOn"/><span class="tip">开启后到点自动发起扫描</span></el-form-item>
        <el-form-item v-if="scheduleOn" label="发起时间" required>
          <template v-if="isAiScheduleTask">
            <div class="schedule-ai-row">
              <el-date-picker
                v-model="aiScheduleDate"
                type="date"
                placeholder="选择日期"
                value-format="yyyy-MM-dd"
                :picker-options="aiDatePickerOptions"
                style="width:180px"
                @change="syncAiScheduleTime"/>
              <el-select
                v-model="aiScheduleHm"
                placeholder="选择时刻"
                filterable
                style="width:140px;margin-left:8px"
                @change="syncAiScheduleTime">
                <el-option v-for="t in aiAllowedTimeOptions" :key="t" :label="t" :value="t"/>
              </el-select>
            </div>
            <div class="tip" style="margin-left:0;margin-top:6px;display:block">
              仅可选择管理员配置窗口内的时刻：{{ aiScheduleStart }} ~ {{ aiScheduleEnd }}{{ aiScheduleCrossDay ? '（跨天）' : '' }}
            </div>
          </template>
          <template v-else-if="!form.ruleIds || !form.ruleIds.length">
            <div class="tip" style="margin-left:0">请先选择扫描规则。AI 规则将使用受限时刻选择器，普通规则可自由选择时间。</div>
          </template>
          <template v-else>
            <el-date-picker
              v-model="form.scheduleTime"
              type="datetime"
              placeholder="选择发起时间"
              value-format="yyyy-MM-dd HH:mm:00"
              format="yyyy-MM-dd HH:mm"
              :picker-options="pickerOptions"
              style="width:100%"/>
          </template>
        </el-form-item>
        <el-form-item v-if="!form.id && !scheduleOn" label="生成后启动"><el-switch v-model="form.executeImmediately"/></el-form-item>
      </el-form>
      <span slot="footer"><el-button @click="visible=false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存并生成清单</el-button></span>
    </el-dialog>

    <el-dialog title="任务详情与快照" :visible.sync="detailVisible" width="780px"><div v-if="selected" class="detail-grid"><div><label>任务编号</label>{{selected.taskNo}}</div><div><label>任务类型</label>{{selected.taskType}}</div><div><label>状态</label>{{selected.status}}</div><div><label>当前快照</label>V{{selected.snapshotVersion||'-'}}</div><div><label>清单状态</label>{{selected.manifestStatus}}</div><div><label>清单文件</label>{{selected.manifestFileCount||0}}</div><div><label>扫描源</label>{{selected.repositoryName}}</div><div><label>开始时间</label>{{selected.startTime||'-'}}</div><div><label>完成文件</label>{{selected.completedFiles}} / {{selected.totalFiles}}</div><div><label>成功/失败</label>{{selected.successFiles}} / {{selected.failedFiles}}</div><div><label>问题数</label>{{selected.issueCount}}</div><div><label>错误信息</label>{{selected.errorMessage||'-'}}</div></div><el-divider>快照历史</el-divider><el-table :data="snapshotRows" size="mini"><el-table-column prop="snapshotVersion" label="版本" width="80"/><el-table-column prop="taskType" label="类型" width="90"/><el-table-column prop="snapshotHash" label="快照摘要" show-overflow-tooltip/><el-table-column prop="createdByUserName" label="保存人" width="120"/><el-table-column prop="createTime" label="保存时间" width="170"/></el-table></el-dialog>

    <el-dialog title="扫描清单预览" :visible.sync="manifestVisible" width="1180px"><div v-if="manifest" class="manifest-summary"><el-descriptions :column="3" border><el-descriptions-item label="清单版本">V{{manifest.manifestVersion}}</el-descriptions-item><el-descriptions-item label="状态">{{manifest.status}}</el-descriptions-item><el-descriptions-item label="文件数">{{manifest.fileCount}}</el-descriptions-item><el-descriptions-item label="排除数量">{{manifest.excludedCount}}</el-descriptions-item><el-descriptions-item label="总大小">{{formatSize(manifest.totalSize)}}</el-descriptions-item><el-descriptions-item label="生成时间">{{manifest.finishTime||'-'}}</el-descriptions-item><el-descriptions-item label="清单摘要" :span="3">{{manifest.manifestHash||'-'}}</el-descriptions-item></el-descriptions><div class="toolbar manifest-filter"><el-input v-model="manifestQuery.keyword" clearable placeholder="文件相对路径" @keyup.enter.native="loadManifestFiles"/><el-input v-model="manifestQuery.fileType" clearable placeholder="文件类型" @keyup.enter.native="loadManifestFiles"/><el-button type="primary" @click="loadManifestFiles">筛选</el-button><el-button @click="regenerate">重新生成</el-button><span class="tip">绿勾=成功 · 转圈=扫描中 · 红叉=失败 · 绿色文件名=可查看AI请求/返回报文</span></div><el-table :data="manifestFiles" height="360" highlight-current-row><el-table-column label="扫描" width="70" align="center"><template slot-scope="s"><span :title="scanStatusTitle(s.row)"><i v-if="isScanning(s.row)" class="el-icon-loading scan-icon scan-running"/><i v-else-if="s.row.scanStatus==='SUCCESS'" class="el-icon-success scan-icon scan-ok"/><i v-else-if="s.row.scanStatus==='FAILED'" class="el-icon-error scan-icon scan-fail"/><i v-else class="el-icon-minus scan-icon scan-none"/></span></template></el-table-column><el-table-column prop="relativePath" label="相对路径" min-width="340" show-overflow-tooltip><template slot-scope="s"><span :class="{'path-ai': s.row.hasAiResponse}">{{s.row.relativePath}}</span></template></el-table-column><el-table-column prop="fileType" label="类型" width="80"/><el-table-column label="大小" width="100"><template slot-scope="s">{{formatSize(s.row.fileSize)}}</template></el-table-column><el-table-column label="请求字符" width="100"><template slot-scope="s">{{s.row.requestChars!=null?formatNumber(s.row.requestChars):'-'}}</template></el-table-column><el-table-column label="请求Token" width="100"><template slot-scope="s">{{s.row.promptTokens!=null?formatNumber(s.row.promptTokens):'-'}}</template></el-table-column><el-table-column prop="applicableRuleCount" label="适用规则" width="80"/><el-table-column label="AI返回报文" width="110" align="center"><template slot-scope="s"><el-button v-if="s.row.hasAiResponse" type="success" size="mini" plain @click.stop="openAiResponses(s.row)">查看</el-button><span v-else class="muted">-</span></template></el-table-column></el-table><el-pagination class="pager" layout="total,prev,pager,next" :total="manifestTotal" :page-size="manifestQuery.pageSize" @current-change="p=>{manifestQuery.pageNum=p;loadManifestFiles()}"/></div></el-dialog>
    <el-dialog :title="'AI请求/返回报文 — '+(aiPayload&&aiPayload.relativePath||'')" :visible.sync="aiVisible" width="960px" append-to-body>
      <div v-loading="aiLoading">
        <div v-if="aiPayload" class="ai-meta"><span>runId: {{aiPayload.runId}}</span><span>units: {{(aiPayload.units||[]).length}}</span></div>
        <div v-if="aiPayload&&!(aiPayload.units||[]).length" class="muted">当前运行暂无执行单元或尚未调用 AI</div>
        <div v-for="unit in (aiPayload&&aiPayload.units)||[]" :key="unit.unitId" class="ai-unit">
          <div class="ai-unit-head"><b>unit #{{unit.unitId}}</b><span>{{unit.stage}}</span><span>{{unit.status}}</span><span v-if="unit.ruleId">ruleId={{unit.ruleId}}</span></div>
          <pre v-if="unit.errorMessage" class="ai-error">{{unit.errorMessage}}</pre>
          <template v-if="unit.aiResponse && unit.aiResponse.calls && unit.aiResponse.calls.length">
            <div v-for="(call, idx) in unit.aiResponse.calls" :key="unit.unitId+'-'+idx" class="ai-call">
              <div class="ai-call-head">调用 {{idx+1}} · {{call.stage||'-'}} · {{call.costMs!=null?(call.costMs+'ms'):''}}</div>
              <div class="ai-block-title">AI请求报文</div>
              <pre class="ai-json">{{formatJson(call.rawRequest||{systemChars:call.systemChars,userChars:call.userChars,requestChars:call.requestChars,tip:'历史数据未保存完整请求体，请重新扫描'})}}</pre>
              <div class="ai-block-title">AI返回报文</div>
              <pre class="ai-json">{{formatJson(call.rawResponse||call.content)}}</pre>
            </div>
          </template>
          <template v-else>
            <div class="ai-block-title">完整落库 JSON</div>
            <pre class="ai-json">{{formatJson(unit.aiResponse)}}</pre>
          </template>
        </div>
      </div>
    </el-dialog>
    <el-dialog title="扫描结果详情" :visible.sync="resultVisible" width="720px"><template v-if="selectedResult"><h3>{{selectedResult.taskName}}</h3><div class="metrics"><div class="metric"><b>{{selectedResult.scannedFiles}}</b>扫描文件</div><div class="metric"><b class="danger">{{selectedResult.issueCount}}</b>问题总数</div><div class="metric"><b>{{selectedResult.highCount}}</b>高风险</div><div class="metric"><b>{{selectedResult.failedFiles}}</b>失败文件</div></div><div class="detail-grid"><div><label>任务编号</label>{{selectedResult.taskNo}}</div><div><label>应用</label>{{selectedResult.application||'-'}}</div><div><label>版本</label>{{versionLabel(selectedResult.versionNo)}}</div><div><label>扫描源</label>{{selectedResult.repositoryName}}</div><div><label>成功文件</label>{{selectedResult.successFiles}}</div><div><label>完成时间</label>{{selectedResult.createTime}}</div></div></template></el-dialog>
  </div>
</template>
<script>
import { taskApi, repoApi, ruleApi, resultApi, modelApi } from '../api'
import { SCOPE_PRESETS, mergeScopePresets } from '../constants/scopePresets'
import { APPLICATIONS } from '../constants/applications'
import { MD_VERSION_MOCK } from '../constants/mdVersions'

const nowString = () => { const d = new Date(); const pad = n => String(n).padStart(2, '0'); return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:00` }
const empty = () => ({ taskName: '', description: '', repositoryId: null, application: '', versionNo: '', ruleIds: [], scanPathsInput: '/', fileTypesInput: 'java,js,ts,vue,xml,yml,yaml,json,md,txt,sql,docx,pdf', excludePathsInput: '.git,node_modules,target', executeImmediately: false, scheduleType: 'NONE', scheduleTime: nowString() })
const split = value => (value || '').split(',').map(item => item.trim()).filter(Boolean)

export default {
  name: 'TaskView',
  data: () => ({
    activeTab: 'tasks',
    q: { keyword: '', status: '', application: '', versionNo: '', pageNum: 1, pageSize: 20 },
    applications: APPLICATIONS,
    versionOptions: MD_VERSION_MOCK,
    states: ['DRAFT', 'READY', 'SCHEDULED', 'QUEUED', 'RUNNING', 'STOP_REQUESTED', 'PAUSED', 'SUCCESS', 'PARTIAL_SUCCESS', 'FAILED', 'ABORTED'],
    rows: [], total: 0, loading: false, visible: false, saving: false,
    detailVisible: false, manifestVisible: false, selected: null, snapshotRows: [],
    repos: [], rules: [], form: empty(), scopePresets: SCOPE_PRESETS, scopePresetKeys: [],
    manifest: null, manifestTask: null,
    pickerOptions: { disabledDate: time => time.getTime() < Date.now() - 86400000 },
    manifestFiles: [], manifestTotal: 0,
    manifestQuery: { keyword: '', fileType: '', pageNum: 1, pageSize: 50 },
    aiVisible: false, aiLoading: false, aiPayload: null, manifestPollTimer: null,
    aiScheduleStart: '20:00', aiScheduleEnd: '08:00',
    aiScheduleDate: '', aiScheduleHm: '',
    resultQuery: { keyword: '', application: '', versionNo: '', pageNum: 1, pageSize: 20 }, resultRows: [], resultTotal: 0, resultLoading: false, resultVisible: false, selectedResult: null, timer: null
  }),
  computed: {
    selectedRepository () { return this.repos.find(item => item.id === this.form.repositoryId) || null },
    selectedScanSourceType () { return this.selectedRepository ? this.selectedRepository.scanSourceType : '' },
    availableRules () { return this.selectedScanSourceType === 'MD' ? this.rules.filter(item => item.ruleType === 'AI') : this.rules },
    selectedRuleType () {
      const ids = (this.form.ruleIds || []).map(id => Number(id))
      const values = this.rules
        .filter(item => ids.indexOf(Number(item.id)) >= 0)
        .map(item => item.ruleType)
      return values.length ? values[0] : ''
    },
    /** 新建/编辑统一：MD 源或已选 AI 规则，都用受限时刻选择器 */
    isAiScheduleTask () {
      return this.selectedScanSourceType === 'MD' || this.selectedRuleType === 'AI' || this.form.taskType === 'AI'
    },
    aiScheduleCrossDay () { return this.aiScheduleStart > this.aiScheduleEnd },
    aiAllowedTimeOptions () {
      const parse = hm => {
        const p = String(hm || '00:00').split(':')
        return Number(p[0]) * 60 + Number(p[1] || 0)
      }
      const fmt = m => {
        const h = Math.floor(m / 60) % 24
        const mm = m % 60
        return String(h).padStart(2, '0') + ':' + String(mm).padStart(2, '0')
      }
      const start = parse(this.aiScheduleStart)
      const end = parse(this.aiScheduleEnd)
      const opts = []
      if (start === end) {
        for (let m = 0; m < 24 * 60; m += 10) opts.push(fmt(m))
        return opts
      }
      if (start < end) {
        for (let m = start; m < end; m += 10) opts.push(fmt(m))
        return opts
      }
      // 跨天：20:00 ~ 次日 08:00
      for (let m = start; m < 24 * 60; m += 10) opts.push(fmt(m))
      for (let m = 0; m < end; m += 10) opts.push(fmt(m))
      return opts
    },
    aiDatePickerOptions () {
      return { disabledDate: time => time.getTime() < Date.now() - 86400000 }
    },
    echoApplication () {
      return this.selectedRepository && this.selectedRepository.application ? this.selectedRepository.application : ''
    },
    echoVersionLabel () {
      if (!this.selectedRepository || !this.selectedRepository.versionNo) return ''
      const hit = MD_VERSION_MOCK.find(item => item.versionNo === this.selectedRepository.versionNo)
      return hit ? hit.label : this.selectedRepository.versionNo
    },
    scheduleOn: {
      get () { return this.form.scheduleType === 'ONCE' },
      set (v) {
        this.form.scheduleType = v ? 'ONCE' : 'NONE'
        if (v) {
          this.form.scheduleTime = this.defaultScheduleTime()
          this.$nextTick(() => this.applySchedulePartsFromForm())
        } else {
          this.aiScheduleDate = ''
          this.aiScheduleHm = ''
        }
      }
    }
  },
  watch: {
    activeTab (value) {
      if (value === 'results') this.loadResults()
    },
    manifestVisible (value) {
      if (value) this.startManifestPoll()
      else this.stopManifestPoll()
    },
    isAiScheduleTask (isAi) {
      if (!this.scheduleOn || !isAi) return
      if (!this.isInAiScheduleWindow(this.form.scheduleTime) || this.aiAllowedTimeOptions.indexOf(this.aiScheduleHm) < 0) {
        this.form.scheduleTime = this.nextAiWindowStartString()
      }
      this.$nextTick(() => this.applySchedulePartsFromForm())
    },
    'form.ruleIds' () {
      if (!this.scheduleOn) return
      if (this.isAiScheduleTask) {
        if (!this.isInAiScheduleWindow(this.form.scheduleTime)) this.form.scheduleTime = this.nextAiWindowStartString()
        this.$nextTick(() => this.applySchedulePartsFromForm())
      }
    }
  },
  created () {
    this.activeTab = this.$route.query.tab === 'results' ? 'results' : 'tasks'
    // 仅当显式带 taskNo 时才作为结果筛选，避免残留关键字把列表滤空
    if (this.$route.query.taskNo) this.resultQuery.keyword = String(this.$route.query.taskNo)
    this.load()
    this.loadResults()
    this.timer = setInterval(() => {
      this.load()
      if (this.activeTab === 'results') this.loadResults()
    }, 4000)
  },
  beforeDestroy () {
    clearInterval(this.timer)
    if (this.manifestPollTimer) { clearInterval(this.manifestPollTimer); this.manifestPollTimer = null }
  },
  methods: {
    /** 分页加载扫描任务列表 */
    async load () { this.loading = true; try { const result = await taskApi.page(this.q); this.rows = result.list; this.total = result.total } finally { this.loading = false } },
    /** 重置任务查询条件并重新加载 */
    resetQuery () { this.q = { keyword: '', status: '', application: '', versionNo: '', pageNum: 1, pageSize: 20 }; this.load() },
    /** 分页加载扫描结果列表 */
    async loadResults () { this.resultLoading = true; try { const result = await resultApi.page(this.resultQuery); this.resultRows = result.list; this.resultTotal = result.total } finally { this.resultLoading = false } },
    /** 搜索扫描结果：重置到第一页并加载 */
    searchResults () { this.resultQuery.pageNum = 1; this.loadResults() },
    /** 重置结果查询条件并重新加载 */
    resetResultQuery () { this.resultQuery = { keyword: '', application: '', versionNo: '', pageNum: 1, pageSize: 20 }; this.loadResults() },
    /** 打开扫描结果详情弹窗 */
    async resultDetail (row) { this.selectedResult = await resultApi.get(row.id); this.resultVisible = true },
    /** 计算任务扫描进度百分比 */
    percent (row) { return row.totalFiles ? Math.round((row.completedFiles || 0) * 100 / row.totalFiles) : 0 },
    /** 判断任务是否可编辑（运行中的任务不可编辑） */
    canEdit (row) { return !['QUEUED', 'RUNNING', 'STOP_REQUESTED', 'PAUSED'].includes(row.status) },
    /** 判断任务是否可启动（清单就绪且未在运行中） */
    canRun (row) { return row.manifestStatus === 'READY' && !['QUEUED', 'RUNNING', 'STOP_REQUESTED', 'PAUSED'].includes(row.status) },
    /** 拼接扫描源下拉选项的展示文案 */
    repositoryLabel (row) {
      const app = row.application ? ' / ' + row.application : ''
      const ver = row.versionNo ? ' / ' + this.versionLabel(row.versionNo) : ''
      const md = row.scanSourceType === 'MD' ? ` / ${{ OVERVIEW_DESIGN: '概要设计.md', DETAIL_DESIGN: '详细设计.md' }[row.mdDocumentType] || ''}` : ''
      const zip = row.sourceType === 'UPLOAD' ? ` / ZIP：${row.originalFileName || '未上传'}` : ''
      return `[${row.scanSourceType}] ${row.repositoryName}${md}${app}${ver}${zip}`
    },
    /** 根据版本号查找并返回版本标签名称，未配置时返回 '-' */
    versionLabel (versionNo) {
      if (!versionNo) return '-'
      const hit = MD_VERSION_MOCK.find(item => item.versionNo === versionNo)
      return hit ? hit.label : versionNo
    },
    /** 扫描源变化：清空已选规则并回填应用与版本 */
    sourceChanged () {
      this.form.ruleIds = []
      if (this.selectedRepository) {
        this.form.application = this.selectedRepository.application || ''
        this.form.versionNo = this.selectedRepository.versionNo || ''
      } else {
        this.form.application = ''
        this.form.versionNo = ''
      }
    },
    /** 加载扫描源与规则下拉数据（仅启用项），UPLOAD 需存在存储文件 */
    async options () { const [repoResult, ruleResult] = await Promise.all([repoApi.page({ enabled: true, pageSize: 100 }), ruleApi.page({ enabled: true, pageSize: 100 })]); this.repos = repoResult.list.filter(item => item.sourceType !== 'UPLOAD' || Boolean(item.storageKey)); this.rules = ruleResult.list },
    /** 切换作用域预设选中状态并同步合并结果 */
    toggleScopePreset (key) {
      const idx = this.scopePresetKeys.indexOf(key)
      if (idx >= 0) this.scopePresetKeys.splice(idx, 1)
      else this.scopePresetKeys.push(key)
      this.syncScopeFromPresets()
    },
    /** 移除选中的作用域预设并同步合并结果 */
    removeScopePreset (key) {
      this.scopePresetKeys = this.scopePresetKeys.filter(k => k !== key)
      this.syncScopeFromPresets()
    },
    /** 根据预设并集回填表单的文件类型与排除路径 */
    syncScopeFromPresets () {
      const merged = mergeScopePresets(this.scopePresetKeys)
      this.form.fileTypesInput = merged.fileTypes
      this.form.excludePathsInput = merged.excludePatterns
    },
    /** 打开新建/编辑任务弹窗：加载下拉数据、AI 调度窗口并初始化表单 */
    async open (row) {
      await this.options()
      try {
        // 加载管理员配置的 AI 调度窗口
        const window = await modelApi.aiScheduleWindow()
        this.aiScheduleStart = window.start || '20:00'
        this.aiScheduleEnd = window.end || '08:00'
      } catch (e) { /* 使用默认窗口 */ }
      this.scopePresetKeys = []
      if (row) {
        // 编辑：根据任务详情回填表单
        const detail = await taskApi.get(row.id); let scope = {}
        try { scope = JSON.parse(detail.scopeJson || '{}') } catch (e) { scope = {} }
        const ruleIds = (detail.ruleIds || []).map(id => Number(id))
        this.form = Object.assign(empty(), detail, {
          ruleIds,
          scanPathsInput: (scope.scanPaths || ['.']).map(item => item === '.' ? '/' : item).join(','),
          fileTypesInput: (scope.fileTypes || []).join(','),
          excludePathsInput: (scope.excludePaths || []).join(',')
        })
        if (!ruleIds.length) this.$message.info('当前快照未绑定规则，请重新选择同类型规则')
        // 回填应用/版本并同步定时时间组件
        this.$nextTick(() => {
          if (this.selectedRepository) {
            this.form.application = this.selectedRepository.application || detail.application || ''
            this.form.versionNo = this.selectedRepository.versionNo || detail.versionNo || ''
          }
          this.applySchedulePartsFromForm()
        })
      } else this.form = empty()
      this.visible = true
    },
    /** 将表单的定时时间拆分回显到 AI 日期与时刻选择器 */
    applySchedulePartsFromForm () {
      const raw = this.form.scheduleTime
      if (!raw) {
        this.aiScheduleDate = ''
        this.aiScheduleHm = ''
        return
      }
      const text = String(raw).replace('T', ' ')
      const parts = text.split(' ')
      this.aiScheduleDate = parts[0] || ''
      const hm = (parts[1] || '00:00:00').slice(0, 5)
      this.aiScheduleHm = this.aiAllowedTimeOptions.indexOf(hm) >= 0 ? hm : (this.aiAllowedTimeOptions[0] || this.aiScheduleStart)
      this.syncAiScheduleTime()
    },
    /** 将 AI 日期与时刻同步到表单的 scheduleTime（HH:mm:00 格式） */
    syncAiScheduleTime () {
      if (!this.aiScheduleDate || !this.aiScheduleHm) return
      if (this.aiAllowedTimeOptions.indexOf(this.aiScheduleHm) < 0) {
        this.aiScheduleHm = this.aiAllowedTimeOptions[0] || this.aiScheduleStart
      }
      this.form.scheduleTime = this.aiScheduleDate + ' ' + this.aiScheduleHm + ':00'
    },
    /** 校验并保存任务（新增或更新），生成不可变扫描清单 */
    async save () {
      // 校验必填项
      if (!this.form.taskName || !this.form.repositoryId || !this.form.ruleIds.length) return this.$message.warning('请填写任务名称、扫描源和规则')
      // 校验所选规则必须为同类型
      const selectedIds = (this.form.ruleIds || []).map(id => Number(id))
      const selected = this.rules.filter(item => selectedIds.indexOf(Number(item.id)) >= 0)
      if (new Set(selected.map(item => item.ruleType)).size !== 1) return this.$message.warning('一个任务只能选择同类型规则')
      // MD 扫描源只能使用 AI 规则
      if (this.selectedScanSourceType === 'MD' && this.selectedRuleType !== 'AI') return this.$message.warning('MD扫描源只能选择AI规则')
      if (this.scheduleOn) {
        // 定时发起：校验时间合法性
        if (this.isAiScheduleTask) this.syncAiScheduleTime()
        if (!this.form.scheduleTime) return this.$message.warning('请选择定时发起时间')
        if (new Date(this.form.scheduleTime.replace(/-/g, '/')).getTime() <= Date.now()) return this.$message.warning('定时发起时间必须晚于当前时间')
        if (this.isAiScheduleTask) {
          if (this.aiAllowedTimeOptions.indexOf(this.aiScheduleHm) < 0) {
            return this.$message.warning('请从允许的时刻列表中选择 AI 定时时间')
          }
          if (!this.isInAiScheduleWindow(this.form.scheduleTime)) {
            return this.$message.warning('AI 任务定时时间须在 ' + this.aiScheduleStart + ' ~ ' + this.aiScheduleEnd + (this.aiScheduleCrossDay ? '（跨天）' : ''))
          }
        }
        this.form.scheduleType = 'ONCE'
        this.form.executeImmediately = false
      } else {
        // 非定时：清空定时配置
        this.form.scheduleType = 'NONE'
        this.form.scheduleTime = null
      }
      // 组装提交参数，将逗号分隔的输入转为数组
      const payload = Object.assign({}, this.form, { scanPaths: split(this.form.scanPathsInput).map(item => item === '/' ? '.' : item), fileTypes: split(this.form.fileTypesInput), excludePaths: split(this.form.excludePathsInput) })
      this.saving = true
      try { this.form.id ? await taskApi.update(this.form.id, payload) : await taskApi.create(payload); this.visible = false; this.$message.success(this.scheduleOn ? '定时任务已保存，到点将自动发起扫描' : '任务快照及扫描清单已生成'); this.load() } finally { this.saving = false }
    },
    /** 复制任务并生成独立快照 */
    async copy (row) { await taskApi.copy(row.id); this.$message.success('任务已复制并生成独立快照'); this.load() },
    /** 检查 AI 扫描并发是否可用，超过上限时提示并返回 false */
    async ensureAiConcurrencyAvailable (row) {
      if (!row || row.taskType !== 'AI') return true
      try {
        const status = await modelApi.aiScanConcurrency()
        if (status.activeCount >= status.limit) {
          await this.$alert(
            '当前服务器 AI 扫描任务并发已达上限（' + status.activeCount + '/' + status.limit
              + '）。\n请等待已有任务完成后再发起，或联系管理员在「大模型配置 → 运行参数」中调高「AI扫描任务并发数」。',
            '无法启动 AI 扫描',
            { type: 'warning', confirmButtonText: '知道了' }
          )
          return false
        }
      } catch (e) { /* 查询失败时交给后端二次校验 */ }
      return true
    },
    /** 判断给定时间是否落在 AI 调度窗口内（支持跨天） */
    isInAiScheduleWindow (scheduleTime) {
      if (!scheduleTime) return false
      const d = new Date(String(scheduleTime).replace(/-/g, '/'))
      if (isNaN(d.getTime())) return false
      const minutes = d.getHours() * 60 + d.getMinutes()
      const parse = hm => {
        const p = String(hm || '00:00').split(':')
        return Number(p[0]) * 60 + Number(p[1] || 0)
      }
      const start = parse(this.aiScheduleStart)
      const end = parse(this.aiScheduleEnd)
      if (start === end) return true
      if (start < end) return minutes >= start && minutes < end
      return minutes >= start || minutes < end
    },
    /** 定时默认值：AI 任务用管理员配置的窗口开始时刻（下一档），普通任务用当前时间 */
    defaultScheduleTime () {
      return this.isAiScheduleTask ? this.nextAiWindowStartString() : nowString()
    },
    /** 下一个「管理员配置的开始时刻」，若今日该时刻已过则取明日 */
    nextAiWindowStartString () {
      const pad = n => String(n).padStart(2, '0')
      const parts = String(this.aiScheduleStart || '20:00').split(':')
      const hh = Number(parts[0]) || 0
      const mm = Number(parts[1]) || 0
      const d = new Date()
      d.setSeconds(0, 0)
      d.setMilliseconds(0)
      d.setHours(hh, mm, 0, 0)
      if (d.getTime() <= Date.now()) d.setDate(d.getDate() + 1)
      return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:00`
    },
    /** 启动任务：检查并发上限后发起扫描，并在发现代码库更新时提示 */
    async run (row) {
      if (!(await this.ensureAiConcurrencyAvailable(row))) return
      const loading = this.$loading({
        lock: true,
        text: '正在检查代码库是否有更新，如有更新将重新拉取并重建清单…',
        spinner: 'el-icon-loading',
        background: 'rgba(0, 0, 0, 0.35)'
      })
      try {
        const result = await taskApi.run(row.id)
        if (result && result.gitUpdated) {
          this.$message.success(result.message || '发现代码库更新，已重新拉取最新代码并重建清单，扫描已启动')
        } else {
          this.$message.success((result && result.message) || '扫描已启动')
        }
        this.load()
      } catch (e) {
        const msg = (e && e.response && e.response.data && e.response.data.message) || (e && e.message) || ''
        if (msg.indexOf('并发') >= 0) {
          this.$alert(msg, '无法启动 AI 扫描', { type: 'warning', confirmButtonText: '知道了' })
        }
      } finally {
        loading.close()
      }
    },
    /** 停止任务运行 */
    async stop (row) { await taskApi.stop(row.id); this.load() },
    /** 继续已暂停的 AI 任务 */
    async resume (row) {
      if (!(await this.ensureAiConcurrencyAvailable(row))) return
      try {
        await taskApi.resume(row.id)
        this.load()
      } catch (e) {
        const msg = (e && e.response && e.response.data && e.response.data.message) || (e && e.message) || ''
        if (msg.indexOf('并发') >= 0) {
          this.$alert(msg, '无法继续 AI 扫描', { type: 'warning', confirmButtonText: '知道了' })
        }
      }
    },
    /** 放弃任务：二次确认后终止运行 */
    async abort (row) { await this.$confirm('放弃后该运行不能继续，已产生结果仍保留，确认放弃？'); await taskApi.abort(row.id); this.load() },
    /** 删除任务：二次确认后删除（历史快照和结果保留） */
    async remove (row) { await this.$confirm('确认删除任务？历史快照和结果仍保留。'); await taskApi.remove(row.id); this.load() },
    /** 打开任务详情弹窗并加载快照历史 */
    async detail (row) { this.selected = await taskApi.get(row.id); this.snapshotRows = await taskApi.snapshots(row.id); this.detailVisible = true },
    /** 打开扫描清单预览弹窗并加载清单文件 */
    async preview (row) {
      this.manifestTask = row
      this.manifest = await taskApi.manifest(row.id)
      this.manifestQuery = { keyword: '', fileType: '', pageNum: 1, pageSize: 50 }
      await this.loadManifestFiles()
      this.manifestVisible = true
    },
    /** 分页加载当前清单的文件列表 */
    async loadManifestFiles () {
      if (!this.manifest || !this.manifestTask) return
      const result = await taskApi.manifestFiles(this.manifest.id, Object.assign({}, this.manifestQuery, { taskId: this.manifestTask.id }))
      this.manifestFiles = result.list
      this.manifestTotal = result.total
    },
    /** 启动清单文件扫描状态的轮询刷新 */
    startManifestPoll () {
      this.stopManifestPoll()
      this.manifestPollTimer = setInterval(() => {
        if (!this.manifestVisible || !this.manifestTask) return
        const live = this.rows.find(item => item.id === this.manifestTask.id)
        if (live) this.manifestTask = live
        const scanning = ['QUEUED', 'RUNNING', 'STOP_REQUESTED'].includes((this.manifestTask && this.manifestTask.status) || '')
        if (scanning || this.manifestFiles.some(f => this.isScanning(f))) this.loadManifestFiles()
      }, 2000)
    },
    /** 停止清单文件的轮询刷新 */
    stopManifestPoll () {
      if (this.manifestPollTimer) { clearInterval(this.manifestPollTimer); this.manifestPollTimer = null }
    },
    /** 判断文件是否处于扫描中状态 */
    isScanning (row) { return row && (row.scanStatus === 'RUNNING' || row.scanStatus === 'PENDING') },
    /** 生成文件扫描状态的提示文案 */
    scanStatusTitle (row) {
      if (!row) return ''
      if (this.isScanning(row)) return '正在扫描'
      if (row.scanStatus === 'SUCCESS') return '扫描成功'
      if (row.scanStatus === 'FAILED') return row.scanErrorMessage || '扫描失败'
      return '尚未扫描'
    },
    /** 打开文件的 AI 请求/返回报文弹窗 */
    async openAiResponses (row) {
      if (!this.manifestTask || !row) return
      if (!row.hasAiResponse) return this.$message.info('该文件暂无 AI 返回报文')
      this.aiVisible = true; this.aiLoading = true; this.aiPayload = null
      try {
        this.aiPayload = await taskApi.fileAiResponses(this.manifestTask.id, row.id)
      } catch (e) {
        this.aiVisible = false
      } finally { this.aiLoading = false }
    },
    /** 格式化 JSON 数据为可读的缩进文本 */
    formatJson (value) {
      if (value == null) return '（无 AI 报文：该文件尚未扫描或未调用模型）'
      if (typeof value === 'string') {
        try { return JSON.stringify(JSON.parse(value), null, 2) } catch (e) { return value }
      }
      try { return JSON.stringify(value, null, 2) } catch (e) { return String(value) }
    },
    /** 重新生成当前任务的扫描清单并刷新 */
    async regenerate () { await taskApi.regenerateManifest(this.manifestTask.id); this.$message.success('扫描清单已重新生成'); this.manifest = await taskApi.manifest(this.manifestTask.id); this.loadManifestFiles(); this.load() },
    /** 将字节数格式化为可读的大小文本（B/KB/MB） */
    formatSize (value) { const size = Number(value || 0); if (size < 1024) return size + ' B'; if (size < 1024 * 1024) return (size / 1024).toFixed(1) + ' KB'; return (size / 1024 / 1024).toFixed(1) + ' MB' },
    /** 将数字格式化为带千位分隔符的文本 */
    formatNumber (value) { return Number(value || 0).toLocaleString() }
  }
}
</script>
<style scoped>
.manifest-filter{margin-top:16px}.manifest-summary .el-descriptions{margin-bottom:8px}.muted{color:#999}.tip{margin-left:8px;color:#999;font-size:12px}.scope-tag-box{min-height:36px;padding:4px 0}.scope-tag{margin:2px 8px 2px 0;cursor:pointer;border-radius:2px}.scope-tag.is-off{opacity:.55}.scope-tag.is-on{font-weight:600}.ai-meta{display:flex;gap:16px;margin-bottom:12px;color:#666;font-size:12px}.ai-unit{margin-bottom:16px;border-top:1px solid #eee;padding-top:12px}.ai-unit-head{display:flex;gap:12px;flex-wrap:wrap;margin-bottom:8px;font-size:13px}.ai-call{margin:10px 0 16px}.ai-call-head{font-weight:600;margin-bottom:8px;font-size:13px}.ai-block-title{margin:8px 0 4px;color:#606266;font-size:12px}.ai-json,.ai-error{white-space:pre-wrap;word-break:break-word;background:#f7f7f7;border:1px solid #eee;padding:12px;max-height:360px;overflow:auto;font-size:12px;line-height:1.45}.ai-error{background:#fff5f5;border-color:#fde2e2;color:#c45656}.scan-icon{font-size:18px}.scan-running{color:#409eff}.scan-ok{color:#67c23a}.scan-fail{color:#f56c6c}.scan-none{color:#c0c4cc}.path-ai{color:#67c23a;font-weight:600}.schedule-ai-row{display:flex;align-items:center;flex-wrap:wrap}
</style>
