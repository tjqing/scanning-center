/**
 * HTTP 请求工具：带开发态 X-User-Id，统一处理业务错误与未登录跳转
 */
import axios from 'axios'
import { Message } from 'element-ui'
import { clearUser, getUser } from './auth'

const UNAUTHORIZED = 40101

const request = axios.create({ baseURL: '/api/v1', timeout: 120000 })

request.interceptors.request.use(config => {
  const user = getUser()
  if (user && user.id != null) {
    config.headers = config.headers || {}
    config.headers['X-User-Id'] = String(user.id)
  }
  return config
})

function redirectLogin () {
  clearUser()
  const hash = window.location.hash || ''
  if (hash.indexOf('#/login') === 0) return
  window.location.hash = '#/login'
}

function isAuthPublicUrl (url) {
  if (!url) return false
  return url.indexOf('/auth/login') >= 0 && url.indexOf('/auth/logout') < 0
}

request.interceptors.response.use(
  r => {
    if (r.config.responseType === 'blob') return r
    const body = r.data
    if (body.code !== 0) {
      if (body.code === UNAUTHORIZED) {
        Message.error(body.message || '请重新登录')
        if (!isAuthPublicUrl(r.config.url)) redirectLogin()
      } else {
        Message.error(body.message || '操作失败')
      }
      return Promise.reject(new Error(body.message || '操作失败'))
    }
    return body.data
  },
  e => {
    Message.error((e.response && e.response.data && e.response.data.message) || e.message || '网络错误')
    return Promise.reject(e)
  }
)

export default request
