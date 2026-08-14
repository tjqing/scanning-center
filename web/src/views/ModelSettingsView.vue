<template>
  <div class="page">
    <el-tabs v-model="tab">
      <el-tab-pane label="UCID / Token 凭证池" name="credentials">
        <div class="toolbar"><el-alert :title="'启用且有效的凭证数即AI规则扫描CODE和MD时共享的并发数；当前启用 '+enabledCount+' 条'" type="info" :closable="false"/><el-button type="success" @click="editCredential()">新增凭证</el-button></div>
        <el-table :data="credentials" v-loading="loading">
          <el-table-column prop="credentialName" label="凭证名称"/><el-table-column prop="ucid" label="UCID"/><el-table-column prop="tokenMasked" label="Token"/>
          <el-table-column prop="runtimeStatus" label="运行状态" width="120"/><el-table-column prop="lastTestTime" label="最近测试" width="170"/>
          <el-table-column label="启用" width="90"><template slot-scope="s"><el-switch :value="s.row.enabled" @change="status(s.row,$event)"/></template></el-table-column>
          <el-table-column label="操作" width="230"><template slot-scope="s"><el-button size="mini" @click="editCredential(s.row)">编辑</el-button><el-button size="mini" @click="test(s.row)">测试</el-button><el-button size="mini" type="danger" @click="removeCredential(s.row)">删除</el-button></template></el-table-column>
        </el-table>
      </el-tab-pane>
      <el-tab-pane label="内置提示词（管理员）" name="prompts">
        <div class="toolbar"><el-select v-model="promptType" clearable placeholder="提示词类型" @change="loadPrompts"><el-option v-for="v in promptTypes" :key="v" :label="v" :value="v"/></el-select><el-button type="success" @click="editPrompt()">新建版本</el-button></div>
        <el-table :data="prompts"><el-table-column prop="promptType" label="类型"/><el-table-column prop="versionNo" label="版本" width="80"/><el-table-column prop="status" label="状态" width="100"/><el-table-column prop="updateTime" label="更新时间" width="180"/><el-table-column label="内容" show-overflow-tooltip><template slot-scope="s">{{s.row.promptContent}}</template></el-table-column><el-table-column label="操作" width="180"><template slot-scope="s"><el-button size="mini" :disabled="s.row.status!=='DRAFT'" @click="editPrompt(s.row)">编辑</el-button><el-button size="mini" type="primary" :disabled="s.row.status==='ACTIVE'" @click="activate(s.row)">启用</el-button></template></el-table-column></el-table>
      </el-tab-pane>
      <el-tab-pane label="运行参数（管理员）" name="runtime">
        <el-card shadow="never">
          <el-form label-width="200px">
            <el-form-item label="Token失败重试次数">
              <el-input-number v-model="tokenRetryCount" :min="0" :max="10"/>
              <span class="setting-tip">默认3次；不包含首次调用，修改后对后续调度生效。</span>
            </el-form-item>
            <el-form-item label="AI扫描任务并发数">
              <el-input-number v-model="aiScanConcurrency" :min="1" :max="50"/>
              <span class="setting-tip">默认1；同一服务器可同时运行的 AI 类型扫描任务上限；当前占用 {{ aiActiveCount }} / {{ aiScanConcurrency }}。</span>
            </el-form-item>
            <el-form-item label="AI定时发起窗口">
              <el-time-select v-model="aiScheduleStart" :picker-options="{start:'00:00',step:'00:30',end:'23:30'}" placeholder="开始" style="width:120px"/>
              <span class="setting-tip" style="margin:0 8px">至</span>
              <el-time-select v-model="aiScheduleEnd" :picker-options="{start:'00:00',step:'00:30',end:'23:30'}" placeholder="结束" style="width:120px"/>
              <span class="setting-tip">仅约束 AI 任务的定时发起时刻；默认 20:00 ~ 次日 08:00（跨天）。普通任务不受影响。</span>
            </el-form-item>
            <el-alert
              type="info"
              :closable="false"
              title="超过并发上限时，用户发起/继续 AI 扫描会被拦截；AI 定时时间不在窗口内时保存任务会被拒绝，并提示联系管理员调整本参数。"
              style="margin-bottom:16px"/>
            <el-form-item>
              <el-button type="primary" :loading="savingRuntime" @click="saveRuntime">保存配置</el-button>
              <el-button @click="loadRuntime">刷新占用</el-button>
            </el-form-item>
          </el-form>
        </el-card>
      </el-tab-pane>
    </el-tabs>
    <el-dialog :title="credentialForm.id?'编辑模型凭证':'新增模型凭证'" :visible.sync="credentialVisible" width="560px"><el-form label-width="100px"><el-form-item label="凭证名称" required><el-input v-model="credentialForm.credentialName"/></el-form-item><el-form-item label="UCID" required><el-input v-model="credentialForm.ucid"/></el-form-item><el-form-item label="Token" :required="!credentialForm.id"><el-input v-model="credentialForm.token" show-password :placeholder="credentialForm.id?'留空保持原Token':''"/></el-form-item><el-form-item label="启用"><el-switch v-model="credentialForm.enabled"/></el-form-item></el-form><span slot="footer"><el-button @click="credentialVisible=false">取消</el-button><el-button type="primary" @click="saveCredential">保存</el-button></span></el-dialog>
    <el-dialog :title="promptForm.id?'编辑提示词草稿':'新建提示词版本'" :visible.sync="promptVisible" width="760px"><el-form label-width="110px"><el-form-item label="提示词类型" required><el-select v-model="promptForm.promptType" :disabled="Boolean(promptForm.id)"><el-option v-for="v in promptTypes" :key="v" :label="v" :value="v"/></el-select></el-form-item><el-form-item label="提示词内容" required><el-input type="textarea" :rows="12" v-model="promptForm.promptContent"/></el-form-item><el-form-item label="JSON Schema"><el-input type="textarea" :rows="5" v-model="promptForm.jsonSchema" placeholder="可选，用于约束模型JSON响应"/></el-form-item></el-form><span slot="footer"><el-button @click="promptVisible=false">取消</el-button><el-button type="primary" @click="savePrompt">保存草稿</el-button></span></el-dialog>
  </div>
</template>
<script>
import { modelApi } from '../api'
const emptyCredential = () => ({ credentialName: '', ucid: '', token: '', enabled: true })
const emptyPrompt = () => ({ promptType: 'AI_CHECK', promptContent: '', jsonSchema: '' })
export default {
  name: 'ModelSettingsView',
  data: () => ({
    tab: 'credentials', loading: false, credentials: [], prompts: [], promptType: '',
    promptTypes: ['AI_CHECK', 'AI_RESULT_UPDATE', 'MD_CHECK'],
    tokenRetryCount: 3, aiScanConcurrency: 1, aiActiveCount: 0,
    aiScheduleStart: '20:00', aiScheduleEnd: '08:00', savingRuntime: false,
    credentialVisible: false, promptVisible: false,
    credentialForm: emptyCredential(), promptForm: emptyPrompt()
  }),
  computed: {
    enabledCount () { return this.credentials.filter(x => x.enabled && x.runtimeStatus !== 'INVALID').length }
  },
  created () {
    this.loadCredentials()
    this.loadPrompts()
    this.loadRuntime()
  },
  methods: {
    /** 加载模型凭证列表 */
    async loadCredentials () { this.loading = true; try { this.credentials = await modelApi.credentials() } finally { this.loading = false } },
    /** 按类型加载提示词列表 */
    async loadPrompts () { this.prompts = await modelApi.prompts({ type: this.promptType }) },
    /** 加载运行参数（Token 重试次数、AI 并发数、调度窗口） */
    async loadRuntime () {
      this.tokenRetryCount = await modelApi.tokenRetryCount()
      const status = await modelApi.aiScanConcurrency()
      this.aiScanConcurrency = status.limit
      this.aiActiveCount = status.activeCount
      const window = await modelApi.aiScheduleWindow()
      this.aiScheduleStart = window.start || '20:00'
      this.aiScheduleEnd = window.end || '08:00'
    },
    /** 保存运行参数配置并刷新占用 */
    async saveRuntime () {
      if (!this.aiScheduleStart || !this.aiScheduleEnd) return this.$message.warning('请配置 AI 定时发起窗口起止时间')
      this.savingRuntime = true
      try {
        await modelApi.updateTokenRetryCount(this.tokenRetryCount)
        await modelApi.updateAiScanConcurrency(this.aiScanConcurrency)
        await modelApi.updateAiScheduleWindow(this.aiScheduleStart, this.aiScheduleEnd)
        await this.loadRuntime()
        this.$message.success('运行参数已保存')
      } finally { this.savingRuntime = false }
    },
    /** 打开新增/编辑凭证弹窗（编辑时清空 Token 占位） */
    editCredential (row) { this.credentialForm = row ? Object.assign(emptyCredential(), row, { token: '' }) : emptyCredential(); this.credentialVisible = true },
    /** 校验并保存模型凭证 */
    async saveCredential () {
      if (!this.credentialForm.credentialName || !this.credentialForm.ucid || (!this.credentialForm.id && !this.credentialForm.token)) return this.$message.warning('请填写完整凭证信息')
      this.credentialForm.id ? await modelApi.updateCredential(this.credentialForm.id, this.credentialForm) : await modelApi.createCredential(this.credentialForm)
      this.credentialVisible = false; this.$message.success('凭证保存成功'); this.loadCredentials()
    },
    /** 启用 / 停用模型凭证 */
    async status (row, value) { await modelApi.credentialStatus(row.id, value); row.enabled = value },
    /** 测试模型凭证的连接是否可用 */
    async test (row) { await modelApi.testCredential(row.id); this.$message.success('大模型HTTP连接测试成功'); this.loadCredentials() },
    /** 删除模型凭证：二次确认后删除 */
    async removeCredential (row) { await this.$confirm('确认删除该凭证？'); await modelApi.removeCredential(row.id); this.loadCredentials() },
    /** 打开新增/编辑提示词版本弹窗 */
    editPrompt (row) { this.promptForm = row ? Object.assign(emptyPrompt(), row) : emptyPrompt(); this.promptVisible = true },
    /** 校验并保存提示词草稿 */
    async savePrompt () {
      if (!this.promptForm.promptType || !this.promptForm.promptContent) return this.$message.warning('请填写提示词类型和内容')
      this.promptForm.id ? await modelApi.updatePrompt(this.promptForm.id, this.promptForm) : await modelApi.createPrompt(this.promptForm)
      this.promptVisible = false; this.$message.success('提示词草稿保存成功'); this.loadPrompts()
    },
    /** 启用指定版本的提示词：二次确认后启用 */
    async activate (row) {
      await this.$confirm('启用该版本后只影响后续保存的新任务快照，确认继续？')
      await modelApi.activatePrompt(row.id); this.$message.success('提示词版本已启用'); this.loadPrompts()
    }
  }
}
</script>
<style scoped>
.toolbar .el-alert { flex: 1; margin-right: 12px; }
.setting-tip { margin-left: 12px; color: #909399; }
</style>
