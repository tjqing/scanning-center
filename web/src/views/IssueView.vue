<template><div class="page">
  <div class="toolbar">
    <el-input v-model="q.keyword" clearable placeholder="任务、问题标题或文件路径" @keyup.enter.native="search"/>
    <el-select v-model="q.application" clearable placeholder="应用">
      <el-option v-for="app in applications" :key="app" :label="app" :value="app"/>
    </el-select>
    <el-select v-model="q.versionNo" clearable placeholder="版本">
      <el-option v-for="v in versionOptions" :key="v.versionNo" :label="v.label" :value="v.versionNo"/>
    </el-select>
    <el-select v-model="q.risk" clearable placeholder="风险等级">
      <el-option v-for="item in riskOptions" :key="item.value" :value="item.value" :label="item.label"/>
    </el-select>
    <el-select v-model="q.status" clearable placeholder="处理状态">
      <el-option v-for="item in statusOptions" :key="item.value" :value="item.value" :label="item.label"/>
    </el-select>
    <el-button type="primary" @click="search">查询</el-button>
    <el-button @click="resetQuery">重置</el-button>
    <el-button type="success" :loading="exporting" @click="exportExcel">导出 Excel</el-button>
  </div>
  <el-alert v-if="q.resultId" :title="'当前按结果ID过滤：'+q.resultId+'（无问题的扫描在此页会是空列表，这是正常现象）'" type="info" show-icon :closable="false" style="margin-bottom:12px"/>
  <el-table :data="rows" empty-text="暂无问题明细（可能本次扫描未发现问题，或筛选条件过严）">
    <el-table-column prop="taskNo" label="任务编号" width="180"/>
    <el-table-column prop="taskName" label="任务名称" min-width="150"/>
    <el-table-column prop="application" label="应用" width="100"/>
    <el-table-column label="版本" width="140">
      <template slot-scope="s">{{ versionLabel(s.row.versionNo) }}</template>
    </el-table-column>
    <el-table-column prop="title" label="问题标题" min-width="160"/>
    <el-table-column label="风险" width="90">
      <template slot-scope="s">{{ riskLabel(s.row.riskLevel) }}</template>
    </el-table-column>
    <el-table-column prop="repositoryName" label="扫描源" min-width="130"/>
    <el-table-column label="文件位置" min-width="200" show-overflow-tooltip>
      <template slot-scope="s">{{ s.row.filePath }}:{{ s.row.startLine }}</template>
    </el-table-column>
    <el-table-column prop="ruleName" label="扫描规则" min-width="130"/>
    <el-table-column label="状态" width="100">
      <template slot-scope="s">{{ statusLabel(s.row.status) }}</template>
    </el-table-column>
    <el-table-column label="操作" width="100" fixed="right">
      <template slot-scope="s"><el-button size="mini" @click="detail(s.row)">处理</el-button></template>
    </el-table-column>
  </el-table>
  <el-pagination class="pager" layout="total,prev,pager,next" :total="total" @current-change="p=>{q.pageNum=p;load()}"/>
  <el-dialog title="扫描结果明细与处理" :visible.sync="visible" width="800px">
    <template v-if="selected">
      <div class="detail-grid">
        <div><label>任务编号</label>{{ selected.taskNo }}</div>
        <div><label>任务名称</label>{{ selected.taskName }}</div>
        <div><label>应用</label>{{ selected.application || '-' }}</div>
        <div><label>版本</label>{{ versionLabel(selected.versionNo) }}</div>
        <div><label>问题标题</label>{{ selected.title }}</div>
        <div><label>风险等级</label>{{ riskLabel(selected.riskLevel) }}</div>
        <div><label>处理状态</label>{{ statusLabel(selected.status) }}</div>
        <div><label>规则</label>{{ selected.ruleName }}</div>
        <div><label>文件</label>{{ selected.filePath }}</div>
        <div><label>行号</label>{{ selected.startLine }} - {{ selected.endLine }}</div>
        <div><label>问题说明</label>{{ selected.issueDescription }}</div>
        <div><label>整改建议</label>{{ selected.suggestion }}</div>
      </div>
      <h4>命中上下文</h4>
      <div class="code">{{ selected.contextContent }}</div>
      <el-form label-width="90px" style="margin-top:16px">
        <el-form-item label="处理状态">
          <el-select v-model="handle.status">
            <el-option v-for="item in statusOptions" :key="item.value" :value="item.value" :label="item.label"/>
          </el-select>
        </el-form-item>
        <el-form-item label="处理说明"><el-input type="textarea" v-model="handle.comment"/></el-form-item>
      </el-form>
    </template>
    <span slot="footer">
      <el-button @click="visible=false">取消</el-button>
      <el-button type="primary" @click="save">保存处理结果</el-button>
    </span>
  </el-dialog>
</div></template>
<script>
import { resultApi } from '../api'
import { APPLICATIONS } from '../constants/applications'
import { MD_VERSION_MOCK } from '../constants/mdVersions'
import {
  ISSUE_STATUS_OPTIONS,
  RISK_LEVEL_OPTIONS,
  issueStatusLabel,
  riskLevelLabel
} from '../constants/issueDict'

export default {
  name: 'IssueView',
  data () {
    return {
      q: {
        keyword: '',
        application: '',
        versionNo: '',
        risk: '',
        status: '',
        resultId: this.$route.query.resultId || null,
        pageNum: 1,
        pageSize: 20
      },
      applications: APPLICATIONS,
      versionOptions: MD_VERSION_MOCK,
      rows: [],
      total: 0,
      visible: false,
      exporting: false,
      selected: null,
      handle: { status: 'CONFIRMED', comment: '' },
      statusOptions: ISSUE_STATUS_OPTIONS,
      riskOptions: RISK_LEVEL_OPTIONS
    }
  },
  created () { this.load() },
  methods: {
    /** 处理状态码转中文标签 */
    statusLabel: issueStatusLabel,
    /** 风险等级码转中文标签 */
    riskLabel: riskLevelLabel,
    /** 根据版本号查找并返回版本标签名称，未配置时返回 '-' */
    versionLabel (versionNo) {
      if (!versionNo) return '-'
      const hit = MD_VERSION_MOCK.find(item => item.versionNo === versionNo)
      return hit ? hit.label : versionNo
    },
    /** 分页加载问题明细列表 */
    async load () { const r = await resultApi.issues(this.q); this.rows = r.list; this.total = r.total },
    /** 搜索问题：重置到第一页并加载 */
    search () { this.q.pageNum = 1; this.load() },
    /** 重置查询条件：清除筛选并清除 URL 中的 resultId */
    resetQuery () {
      this.q = { keyword: '', application: '', versionNo: '', risk: '', status: '', resultId: null, pageNum: 1, pageSize: 20 }
      if (this.$route.query.resultId) this.$router.replace({ path: '/issues', query: {} })
      this.load()
    },
    /** 打开问题处理弹窗并加载详情与默认处理状态 */
    async detail (r) {
      this.selected = await resultApi.issue(r.id)
      // 待处理默认置为已确认，否则沿用原状态
      this.handle = {
        status: this.selected.status === 'PENDING' ? 'CONFIRMED' : this.selected.status,
        comment: this.selected.handleComment || ''
      }
      this.visible = true
    },
    /** 保存问题的处理结果并刷新列表 */
    async save () {
      await resultApi.status(this.selected.id, this.handle)
      this.visible = false
      this.$message.success('处理成功')
      this.load()
    },
    /** 导出当前筛选条件下的问题明细为 Excel 文件 */
    async exportExcel () {
      this.exporting = true
      try {
        // 移除分页参数后导出全部匹配数据
        const params = Object.assign({}, this.q)
        delete params.pageNum
        delete params.pageSize
        const response = await resultApi.exportIssues(params)
        // 构造临时下载链接并触发下载
        const url = URL.createObjectURL(response.data)
        const link = document.createElement('a')
        link.href = url
        link.download = '扫描结果明细.xlsx'
        document.body.appendChild(link)
        link.click()
        document.body.removeChild(link)
        URL.revokeObjectURL(url)
        this.$message.success('导出成功')
      } finally { this.exporting = false }
    }
  }
}
</script>
