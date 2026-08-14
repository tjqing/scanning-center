<template>
  <div class="login-page">
    <div class="login-card">
      <h2>扫描中心</h2>
      <p class="hint">请输入用户名和密码登录（默认密码 kzxf-123456）</p>
      <el-form label-width="80px" @submit.native.prevent="login">
        <el-form-item label="用户名" required>
          <el-input v-model="username" clearable placeholder="请输入用户名" autocomplete="username" @keyup.enter.native="login"/>
        </el-form-item>
        <el-form-item label="密码" required>
          <el-input v-model="password" type="password" show-password placeholder="请输入密码" autocomplete="current-password" @keyup.enter.native="login"/>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" style="width:100%" :loading="saving" @click="login">登录</el-button>
        </el-form-item>
      </el-form>
    </div>
  </div>
</template>
<script>
import { authApi } from '../api'
import { clearUser, isLoggedIn, setUser } from '../utils/auth'

export default {
  name: 'LoginView',
  data: () => ({ username: '', password: '', saving: false }),
  async created () {
    if (!isLoggedIn()) return
    try {
      const me = await authApi.me()
      setUser(me)
      this.$router.replace(this.redirectTo())
    } catch (e) {
      clearUser()
    }
  },
  methods: {
    redirectTo () {
      const q = this.$route.query.redirect
      return q && String(q).indexOf('/login') < 0 ? String(q) : '/rules'
    },
    async login () {
      const username = (this.username || '').trim()
      if (!username) return this.$message.warning('请输入用户名')
      if (!this.password) return this.$message.warning('请输入密码')
      this.saving = true
      try {
        const user = await authApi.login({ username, password: this.password })
        setUser(user, { renew: true })
        this.password = ''
        this.$message.success('登录成功')
        this.$router.replace(this.redirectTo())
      } finally {
        this.saving = false
      }
    }
  }
}
</script>
<style scoped>
.login-page{min-height:100vh;display:flex;align-items:center;justify-content:center;background:linear-gradient(160deg,#1a2744 0%,#2c3e5a 45%,#f4f6f9 45%)}
.login-card{width:420px;background:#fff;padding:32px 28px;border-radius:8px;box-shadow:0 8px 24px rgba(0,0,0,.12)}
.login-card h2{margin:0 0 8px;text-align:center;color:#17233d}
.hint{margin:0 0 24px;font-size:13px;color:#909399;text-align:center;line-height:1.5}
</style>
