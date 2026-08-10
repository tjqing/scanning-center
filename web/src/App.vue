<template>
  <el-container class="app">
    <el-aside width="220px"><div class="brand">扫描中心</div><el-menu router :default-active="$route.path" background-color="#17233d" text-color="#bfcbd9" active-text-color="#fff">
      <el-menu-item index="/rules"><i class="el-icon-document"></i>扫描规则维护</el-menu-item>
      <el-menu-item index="/repositories"><i class="el-icon-folder-opened"></i>扫描源维护</el-menu-item>
      <el-menu-item index="/tasks"><i class="el-icon-s-operation"></i>扫描任务与结果</el-menu-item>
      <el-menu-item index="/issues"><i class="el-icon-warning-outline"></i>扫描结果明细</el-menu-item>
      <el-menu-item index="/model-settings"><i class="el-icon-connection"></i>大模型配置</el-menu-item>
      <el-menu-item v-if="isAdmin" index="/users"><i class="el-icon-user"></i>用户管理</el-menu-item>
      <el-menu-item v-if="isAdmin" index="/repository-catalogs"><i class="el-icon-collection"></i>代码库维护</el-menu-item>
    </el-menu></el-aside>
    <el-container><el-header><span>代码与文档扫描中心</span><span class="operator">当前操作人：{{ currentUser.id || '-' }} / {{ currentUser.username || '-' }}</span><span class="env">开发环境</span></el-header><el-main><router-view /></el-main></el-container>
  </el-container>
</template>
<script>
import { userApi } from './api'
export default { name: 'App', data: () => ({ currentUser: {} }), computed: { isAdmin () { return this.currentUser.roleCode === 'ADMIN' } }, async created () { try { this.currentUser = await userApi.get(1) } catch (e) { this.currentUser = {} } } }
</script>
