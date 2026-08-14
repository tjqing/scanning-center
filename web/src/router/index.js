/**
 * 前端路由：开发态登录守卫 + 管理员页拦截
 */
import Vue from 'vue'
import Router from 'vue-router'
import { Message } from 'element-ui'
import RuleView from '../views/RuleView.vue'
import RepositoryView from '../views/RepositoryView.vue'
import RepositoryCatalogView from '../views/RepositoryCatalogView.vue'
import TaskView from '../views/TaskView.vue'
import IssueView from '../views/IssueView.vue'
import UserView from '../views/UserView.vue'
import ModelSettingsView from '../views/ModelSettingsView.vue'
import LoginView from '../views/LoginView.vue'
import { getUser, isAdmin, isLoggedIn } from '../utils/auth'

Vue.use(Router)

const router = new Router({
  mode: 'hash',
  routes: [
    { path: '/login', component: LoginView, meta: { public: true, blank: true } },
    { path: '/', redirect: '/rules' },
    { path: '/rules', component: RuleView },
    { path: '/repositories', component: RepositoryView },
    { path: '/repository-catalogs', component: RepositoryCatalogView, meta: { requiresAdmin: true } },
    { path: '/tasks', component: TaskView },
    {
      path: '/results',
      redirect: to => ({ path: '/tasks', query: Object.assign({}, to.query, { tab: 'results' }) })
    },
    { path: '/issues', component: IssueView },
    { path: '/model-settings', component: ModelSettingsView },
    { path: '/users', component: UserView, meta: { requiresAdmin: true } }
  ]
})

router.beforeEach((to, from, next) => {
  if (to.meta && to.meta.public) {
    next()
    return
  }
  if (!isLoggedIn()) {
    next({ path: '/login', query: { redirect: to.fullPath } })
    return
  }
  if (to.meta && to.meta.requiresAdmin && !isAdmin()) {
    Message.warning('仅管理员可访问该页面')
    next(from.path && from.path !== '/login' ? false : '/rules')
    return
  }
  // 刷新 getUser 缓存角色（同页切换后仍有效）
  void getUser()
  next()
})

export default router
