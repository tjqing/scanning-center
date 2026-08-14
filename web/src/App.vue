<template>
  <div class="app-root">
    <div v-if="isLoginPage" class="login-wrap">
      <router-view />
    </div>
    <el-container v-else class="app">
      <el-aside width="220px">
        <div class="brand">扫描中心</div>
        <el-menu router :default-active="$route.path" background-color="#17233d" text-color="#bfcbd9" active-text-color="#fff">
          <el-menu-item index="/rules"><i class="el-icon-document"></i>扫描规则维护</el-menu-item>
          <el-menu-item index="/repositories"><i class="el-icon-folder-opened"></i>扫描源维护</el-menu-item>
          <el-menu-item index="/tasks"><i class="el-icon-s-operation"></i>扫描任务与结果</el-menu-item>
          <el-menu-item index="/issues"><i class="el-icon-warning-outline"></i>扫描结果明细</el-menu-item>
          <el-menu-item index="/model-settings"><i class="el-icon-connection"></i>大模型配置</el-menu-item>
          <el-menu-item v-if="isAdmin" index="/users"><i class="el-icon-user"></i>用户管理</el-menu-item>
          <el-menu-item v-if="isAdmin" index="/repository-catalogs"><i class="el-icon-collection"></i>代码库维护</el-menu-item>
        </el-menu>
      </el-aside>
      <el-container>
        <el-header>
          <span>代码与文档扫描中心</span>
          <span class="header-right">
            <span class="operator">当前操作人：{{ operatorText }}</span>
            <el-button type="text" class="logout-btn" @click="logout">退出</el-button>
            <span class="env">开发环境</span>
          </span>
        </el-header>
        <el-main><router-view /></el-main>
      </el-container>
    </el-container>
  </div>
</template>
<script>
import { authApi } from './api'
import { clearUser, getUser, setUser } from './utils/auth'

export default {
  name: 'App',
  data: () => ({ currentUser: getUser() || {} }),
  computed: {
    isLoginPage () { return this.$route.path === '/login' },
    isAdmin () { return this.currentUser.roleCode === 'ADMIN' },
    operatorText () {
      const u = this.currentUser
      if (!u || !u.id) return '-'
      const name = u.displayName || u.username || '-'
      return u.id + ' / ' + (u.username || '-') + ' / ' + name
    }
  },
  watch: {
    '$route' () {
      if (!this.isLoginPage) this.syncUserFromStore()
    }
  },
  async created () {
    if (this.isLoginPage) return
    await this.refreshMe()
  },
  methods: {
    syncUserFromStore () {
      this.currentUser = getUser() || {}
    },
    async refreshMe () {
      const local = getUser()
      if (!local) {
        this.currentUser = {}
        return
      }
      try {
        const me = await authApi.me()
        setUser(me)
        this.currentUser = me
      } catch (e) {
        this.currentUser = {}
      }
    },
    async logout () {
      try { await authApi.logout() } catch (e) { /* ignore */ }
      clearUser()
      this.currentUser = {}
      this.$router.replace('/login')
    }
  }
}
</script>
<style>
.app-root,.login-wrap{min-height:100vh}
.header-right{display:flex;align-items:center;gap:12px}
.operator{font-size:13px;color:#606266}
.logout-btn{padding:0;font-size:13px}
</style>
