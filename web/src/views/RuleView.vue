<template>
  <div class="page">
    <div class="toolbar">
      <el-input v-model="q.keyword" placeholder="规则名称或编码" clearable @keyup.enter.native="load"/>
      <el-select v-model="q.type" clearable placeholder="规则类型">
        <el-option label="普通规则" value="NORMAL"/>
        <el-option label="AI 规则" value="AI"/>
      </el-select>
      <el-button type="primary" @click="load">查询</el-button>
      <el-button type="success" @click="edit()">新增规则</el-button>
    </div>
    <el-table :data="rows" v-loading="loading">
      <el-table-column prop="ruleCode" label="规则编码"/>
      <el-table-column prop="ruleName" label="规则名称"/>
      <el-table-column prop="ruleType" label="类型" width="90"/>
      <el-table-column prop="visibility" label="范围" width="90"/>
      <el-table-column prop="ownerUserName" label="所有者" width="110"/>
      <el-table-column prop="riskLevel" label="风险" width="90"/>
      <el-table-column label="状态" width="90">
        <template slot-scope="s"><el-switch :value="s.row.enabled" @change="status(s.row,$event)"/></template>
      </el-table-column>
      <el-table-column label="操作" width="260">
        <template slot-scope="s">
          <el-button size="mini" @click="edit(s.row)">编辑</el-button>
          <el-button size="mini" @click="copy(s.row)">复制</el-button>
          <el-button size="mini" type="danger" @click="remove(s.row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination class="pager" background layout="total,prev,pager,next" :total="total" :page-size="q.pageSize" @current-change="p=>{q.pageNum=p;load()}"/>
    <el-dialog :title="form.id?'编辑扫描规则':'新增扫描规则'" :visible.sync="visible" width="720px">
      <el-form :model="form" label-width="110px">
        <el-form-item label="规则编码"><el-input v-model="form.ruleCode"/></el-form-item>
        <el-form-item label="规则名称"><el-input v-model="form.ruleName"/></el-form-item>
        <el-form-item label="规则类型">
          <el-radio-group v-model="form.ruleType">
            <el-radio label="NORMAL">普通</el-radio>
            <el-radio label="AI">AI</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="可见范围">
          <el-radio-group v-model="form.visibility">
            <el-radio label="PRIVATE">私有</el-radio>
            <el-radio label="SHARED">共享</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="扫描对象">
          <el-select v-model="form.targetType">
            <el-option label="全部" value="ALL"/>
            <el-option label="代码" value="CODE"/>
            <el-option label="文档" value="DOCUMENT"/>
          </el-select>
        </el-form-item>
        <el-form-item label="风险等级">
          <el-select v-model="form.riskLevel">
            <el-option v-for="v in ['HIGH','MEDIUM','LOW','INFO']" :key="v" :value="v" :label="v"/>
          </el-select>
        </el-form-item>
        <template v-if="form.ruleType==='NORMAL'">
          <el-form-item label="匹配方式">
            <el-radio-group v-model="form.matchType">
              <el-radio label="KEYWORD">关键字</el-radio>
              <el-radio label="REGEX">正则</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="匹配内容">
            <el-input type="textarea" :rows="3" v-model="form.matchContent"/>
            <div class="hint">扫描时先按语言去除注释，再执行匹配。</div>
          </el-form-item>
        </template>
        <template v-else>
          <el-form-item label="检查规则"><el-input type="textarea" :rows="6" v-model="form.checkRuleContent"/></el-form-item>
          <el-form-item label="结果更新"><el-input type="textarea" :rows="5" v-model="form.resultUpdateContent"/></el-form-item>
        </template>
        <el-form-item label="作用域预设">
          <div class="scope-tag-box">
            <el-tag
              v-for="p in scopePresets"
              :key="p.key"
              :type="scopePresetKeys.indexOf(p.key) >= 0 ? p.tagType : 'info'"
              :closable="scopePresetKeys.indexOf(p.key) >= 0"
              :class="['scope-tag', scopePresetKeys.indexOf(p.key) >= 0 ? 'is-on' : 'is-off']"
              @click.native="toggleScopePreset(p.key)"
              @close="removeScopePreset(p.key)"
            >{{ p.shortName }}</el-tag>
          </div>
          <div class="hint">点击标签选中，点叉取消；可多选，文件类型与排除目录按并集合并，仍可手工改。</div>
        </el-form-item>
        <el-form-item label="文件类型"><el-input v-model="form.fileTypes" placeholder="java,js,md；英文逗号分隔"/></el-form-item>
        <el-form-item label="排除目录"><el-input v-model="form.excludePatterns" placeholder="target,node_modules；英文逗号分隔"/></el-form-item>
        <el-form-item label="问题说明"><el-input v-model="form.issueDescription"/></el-form-item>
        <el-form-item label="整改建议"><el-input v-model="form.suggestion"/></el-form-item>
        <el-form-item label="启用"><el-switch v-model="form.enabled"/></el-form-item>
      </el-form>
      <span slot="footer">
        <el-button @click="visible=false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </span>
    </el-dialog>
  </div>
</template>
<script>
import { ruleApi } from '../api'
import { SCOPE_PRESETS, mergeScopePresets } from '../constants/scopePresets'

const empty = () => ({
  ruleCode: '', ruleName: '', ruleType: 'NORMAL', visibility: 'PRIVATE', targetType: 'ALL', riskLevel: 'MEDIUM',
  matchType: 'KEYWORD', matchContent: '', caseSensitive: false, fileTypes: '', excludePatterns: '',
  issueDescription: '', suggestion: '', checkRuleContent: '', resultUpdateContent: '', enabled: true
})
const commaList = (v, pattern) => {
  if (!v) return true
  if (/[，；;]/.test(v) || v.startsWith(',') || v.endsWith(',') || v.includes(',,')) return false
  return v.split(',').every(x => x.trim() && pattern.test(x.trim()))
}

export default {
  name: 'RuleView',
  data: () => ({
    q: { keyword: '', type: '', pageNum: 1, pageSize: 20 },
    rows: [], total: 0, loading: false, visible: false, form: empty(),
    scopePresets: SCOPE_PRESETS, scopePresetKeys: []
  }),
  created () { this.load() },
  methods: {
    /** 分页加载规则列表数据 */
    async load () {
      this.loading = true
      try {
        const r = await ruleApi.page(this.q)
        this.rows = r.list
        this.total = r.total
      } finally { this.loading = false }
    },
    /** 打开新增/编辑弹窗：传入 row 时编辑，否则新增 */
    edit (r) {
      this.form = r ? Object.assign(empty(), r) : empty()
      this.scopePresetKeys = []
      this.visible = true
    },
    /** 切换作用域预设的选中状态（点击标签），并同步合并结果 */
    toggleScopePreset (key) {
      const idx = this.scopePresetKeys.indexOf(key)
      if (idx >= 0) this.scopePresetKeys.splice(idx, 1)
      else this.scopePresetKeys.push(key)
      this.syncScopeFromPresets()
    },
    /** 移除已选中的作用域预设（点叉关闭），并同步合并结果 */
    removeScopePreset (key) {
      this.scopePresetKeys = this.scopePresetKeys.filter(k => k !== key)
      this.syncScopeFromPresets()
    },
    /** 根据已选预设的并集回填表单的文件类型与排除目录 */
    syncScopeFromPresets () {
      const merged = mergeScopePresets(this.scopePresetKeys)
      this.form.fileTypes = merged.fileTypes
      this.form.excludePatterns = merged.excludePatterns
    },
    /** 校验并保存规则（新增或更新），成功后刷新列表 */
    async save () {
      // 校验规则编码与名称必填
      if (!this.form.ruleCode || !this.form.ruleName) return this.$message.warning('请填写规则编码和名称')
      // 校验规则编码格式（字母/数字/下划线/中划线）
      if (!/^[A-Za-z0-9_-]+$/.test(this.form.ruleCode)) return this.$message.warning('规则编码格式不正确')
      // 校验文件类型与排除目录格式
      if (!commaList(this.form.fileTypes, /^[A-Za-z0-9]+(?:[._+-][A-Za-z0-9]+)*$/) || !commaList(this.form.excludePatterns, /^[^,，;；\s]+$/)) {
        return this.$message.warning('文件类型或排除目录格式不正确')
      }
      // 普通规则需填写匹配内容
      if (this.form.ruleType === 'NORMAL' && !this.form.matchContent) return this.$message.warning('请填写匹配内容')
      // 正则匹配需校验正则表达式合法性
      if (this.form.ruleType === 'NORMAL' && this.form.matchType === 'REGEX') {
        try { new RegExp(this.form.matchContent) } catch (e) { return this.$message.warning('正则表达式格式不正确') }
      }
      // AI 规则需填写检查规则与结果更新内容
      if (this.form.ruleType === 'AI' && (!this.form.checkRuleContent || !this.form.resultUpdateContent)) {
        return this.$message.warning('AI规则必须填写检查规则和结果更新')
      }
      // 有 id 则更新，否则新增
      this.form.id ? await ruleApi.update(this.form.id, this.form) : await ruleApi.create(this.form)
      this.visible = false
      this.$message.success('保存成功；已有任务快照不会自动变化')
      this.load()
    },
    /** 启用 / 停用规则 */
    async status (r, v) { await ruleApi.status(r.id, v); r.enabled = v },
    /** 复制规则并刷新列表 */
    async copy (r) { await ruleApi.copy(r.id); this.$message.success('复制成功'); this.load() },
    /** 删除规则：二次确认后删除并刷新列表 */
    async remove (r) {
      await this.$confirm('确认删除该规则？已有任务快照仍保留。')
      await ruleApi.remove(r.id)
      this.load()
    }
  }
}
</script>
<style scoped>
.scope-tag-box { min-height: 36px; padding: 4px 0; }
.scope-tag { margin: 2px 8px 2px 0; cursor: pointer; border-radius: 2px; }
.scope-tag.is-off { opacity: 0.55; }
.scope-tag.is-on { font-weight: 600; }
.hint { color: #909399; font-size: 12px; line-height: 1.5; margin-top: 4px; }
</style>
