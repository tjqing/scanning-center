<template><div class="page">
  <div class="toolbar"><el-input v-model="q.keyword" placeholder="任务、问题标题或文件路径" @keyup.enter.native="load"/><el-select v-model="q.risk" clearable placeholder="风险等级"><el-option v-for="v in ['HIGH','MEDIUM','LOW','INFO']" :key="v" :value="v" :label="v"/></el-select><el-select v-model="q.status" clearable placeholder="处理状态"><el-option v-for="v in ['PENDING','CONFIRMED','RESOLVED','IGNORED']" :key="v" :value="v" :label="v"/></el-select><el-button type="primary" @click="load">查询</el-button><el-button type="success" :loading="exporting" @click="exportExcel">导出 Excel</el-button></div>
  <el-table :data="rows"><el-table-column prop="taskNo" label="任务编号" width="180"/><el-table-column prop="taskName" label="任务名称" min-width="150"/><el-table-column prop="title" label="问题标题" min-width="160"/><el-table-column prop="riskLevel" label="风险" width="90"/><el-table-column prop="repositoryName" label="扫描源" min-width="130"/><el-table-column prop="filePath" label="文件位置" min-width="200" show-overflow-tooltip><template slot-scope="s">{{s.row.filePath}}:{{s.row.startLine}}</template></el-table-column><el-table-column prop="ruleName" label="扫描规则" min-width="130"/><el-table-column prop="status" label="状态" width="100"/><el-table-column label="操作" width="100" fixed="right"><template slot-scope="s"><el-button size="mini" @click="detail(s.row)">处理</el-button></template></el-table-column></el-table>
  <el-pagination class="pager" layout="total,prev,pager,next" :total="total" @current-change="p=>{q.pageNum=p;load()}"/>
  <el-dialog title="扫描结果明细与处理" :visible.sync="visible" width="800px"><template v-if="selected"><div class="detail-grid"><div><label>任务编号</label>{{selected.taskNo}}</div><div><label>任务名称</label>{{selected.taskName}}</div><div><label>问题标题</label>{{selected.title}}</div><div><label>风险等级</label>{{selected.riskLevel}}</div><div><label>规则</label>{{selected.ruleName}}</div><div><label>文件</label>{{selected.filePath}}</div><div><label>行号</label>{{selected.startLine}} - {{selected.endLine}}</div><div><label>问题说明</label>{{selected.issueDescription}}</div><div><label>整改建议</label>{{selected.suggestion}}</div></div><h4>命中上下文</h4><div class="code">{{selected.contextContent}}</div><el-form label-width="90px" style="margin-top:16px"><el-form-item label="处理状态"><el-select v-model="handle.status"><el-option label="已确认" value="CONFIRMED"/><el-option label="已处理" value="RESOLVED"/><el-option label="已忽略" value="IGNORED"/></el-select></el-form-item><el-form-item label="处理说明"><el-input type="textarea" v-model="handle.comment"/></el-form-item></el-form></template><span slot="footer"><el-button @click="visible=false">取消</el-button><el-button type="primary" @click="save">保存处理结果</el-button></span></el-dialog>
</div></template>
<script>
import { resultApi } from '../api'
export default {
  name: 'IssueView',
  data () { return { q: { keyword: '', risk: '', status: '', resultId: this.$route.query.resultId || null, pageNum: 1, pageSize: 20 }, rows: [], total: 0, visible: false, exporting: false, selected: null, handle: { status: 'CONFIRMED', comment: '' } } },
  created () { this.load() },
  methods: {
    async load () { const r = await resultApi.issues(this.q); this.rows = r.list; this.total = r.total },
    async detail (r) { this.selected = await resultApi.issue(r.id); this.handle = { status: this.selected.status === 'PENDING' ? 'CONFIRMED' : this.selected.status, comment: this.selected.handleComment || '' }; this.visible = true },
    async save () { await resultApi.status(this.selected.id, this.handle); this.visible = false; this.$message.success('处理成功'); this.load() },
    async exportExcel () {
      this.exporting = true
      try {
        const params = Object.assign({}, this.q)
        delete params.pageNum
        delete params.pageSize
        const response = await resultApi.exportIssues(params)
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
