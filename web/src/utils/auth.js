/**
 * 开发态登录会话（localStorage）
 * 后续接 SSO 时替换读写与登录页即可
 */
const KEY = 'scan-center-user'
/** 本地会话有效期：8 小时（绝对时间，登录时起算） */
const SESSION_TTL_MS = 8 * 60 * 60 * 1000

function readRaw () {
  try {
    const raw = localStorage.getItem(KEY)
    if (!raw) return null
    return JSON.parse(raw)
  } catch (e) {
    return null
  }
}

function isExpired (user) {
  if (!user || user.id == null) return true
  const expiresAt = Number(user.expiresAt)
  if (!expiresAt || Number.isNaN(expiresAt)) return true
  return Date.now() >= expiresAt
}

export function getUser () {
  const user = readRaw()
  if (!user || user.id == null) return null
  if (isExpired(user)) {
    clearUser()
    return null
  }
  return user
}

/**
 * 写入本地会话。
 * @param user 用户简要信息
 * @param options.renew 为 true 时重新起算 8 小时（登录成功时传 true）；否则保留原 expiresAt
 */
export function setUser (user, options) {
  if (!user || !user.id) {
    clearUser()
    return
  }
  const renew = options && options.renew
  let expiresAt
  if (renew) {
    expiresAt = Date.now() + SESSION_TTL_MS
  } else {
    const prev = readRaw()
    if (prev && prev.expiresAt && !isExpired(prev)) {
      expiresAt = Number(prev.expiresAt)
    } else {
      expiresAt = Date.now() + SESSION_TTL_MS
    }
  }
  localStorage.setItem(KEY, JSON.stringify({
    id: user.id,
    username: user.username,
    displayName: user.displayName,
    roleCode: user.roleCode,
    application: user.application,
    expiresAt: expiresAt
  }))
}

export function clearUser () {
  localStorage.removeItem(KEY)
}

export function isLoggedIn () {
  return Boolean(getUser())
}

export function isAdmin () {
  const user = getUser()
  return Boolean(user && user.roleCode === 'ADMIN')
}
