/**
 * 底部流水线导航 · 任务线路同步状态机（框架无关，可直接被 node --test 单测）
 *
 * 背景：系统有多条运行线路时，旧实现底部导航始终取第一条运行线的工位；选中其他线路的任务后，
 * 胶囊只移动了序号，节点与录入弹窗仍是第一条线的工位，提交被后端以「工位不属于任务所在检测线」拒绝。
 *
 * 同步口径：
 * 1. 选中任务必须加载其所属线路的节点（bindTask / resyncTask 发现换线时先 reload 再落任务）；
 * 2. 没有当前任务时保留最近线路：activeLineId 持久化（localStorage，不可用时退化为内存），
 *    刷新页面后先恢复最近线路（restore()），再决定默认线 —— 保持刷新后的页面连续性；
 * 3. 定时刷新、任务结束（自动离开在检队列）、跨线转线后：当前任务仍在则对齐其最新线路，
 *    任务消失则保留其线路不清空，节点永不与当前任务错配；
 * 4. 点击节点只允许录入当前线路的当前工位（inspectNode 闸门，跨线/无任务/非当前工位一律拒绝）。
 */

export const LAST_LINE_STORAGE_KEY = 'vc.pipeline.activeLineId'

/** inspectNode 拒绝原因 → 页面提示语 */
export const NODE_REJECT_MESSAGES = {
  NO_TASK: '请先选择一辆在检车辆',
  OTHER_LINE: '该工位属于其他检测线，请先切换到当前任务所在线路',
  NOT_CURRENT: '只能录入车辆当前所在工位，请按顺序流转'
}

function memoryStorage () {
  const map = new Map()
  return {
    getItem: (k) => (map.has(k) ? map.get(k) : null),
    setItem: (k, v) => map.set(k, String(v)),
    removeItem: (k) => map.delete(k)
  }
}

function safeStorage (storage) {
  // 隐私模式 / SSR / 禁用 localStorage 时退化为内存实现，避免恢复流程抛错
  try {
    const probe = '__vc_probe__'
    storage.setItem(probe, '1')
    storage.removeItem(probe)
    return storage
  } catch (e) {
    return memoryStorage()
  }
}

/**
 * @param {object} deps
 * @param {(lineId?: number|string|null) => Promise<Array>} deps.fetchStations 按线路取工位（lineId 为空取默认线）
 * @param {Storage} [deps.storage] 持久化最近线路
 * @param {string} [deps.storageKey]
 * @param {(state: object) => object} [deps.wrapState] 状态包装器（Vue 侧传入 reactive，使方法写入具备响应性）
 */
export function createLineSync ({ fetchStations, storage, storageKey = LAST_LINE_STORAGE_KEY, wrapState } = {}) {
  if (typeof fetchStations !== 'function') {
    throw new Error('createLineSync 缺少 fetchStations(lineId) 依赖')
  }
  const store = safeStorage(storage || (typeof localStorage !== 'undefined' ? localStorage : memoryStorage()))

  // Vue 侧传 reactive 作为 wrapState：状态机内部所有写入都经代理触发视图更新；
  // 纯 JS 单测不传，保持普通对象。
  const state = (wrapState || ((s) => s))({
    /** 当前导航展示的线路；null 表示尚未恢复（首次加载中） */
    activeLineId: null,
    activeLineNo: '',
    /** activeLineId 对应的节点；不变量：stations 与 activeLineId 始终同线 */
    stations: [],
    /** 当前选中的在检任务（仅保留头部字段）；null = 无当前任务 */
    currentTask: null,
    capsule: { plate: '', seq: 0 },
    /** 节点点击待处理载荷（{node, nonce}，FlowView watch nonce 消费） */
    pendingNode: null,
    pendingNonce: 0,
    /** 定时刷新 tick 计数；FlowView watch 后重拉当前任务详情，对齐最新工位/线路/结束状态 */
    resyncNonce: 0,
    ready: false
  })

  let restoredPromise = null

  function persistLine (lineId) {
    if (lineId == null) {
      store.removeItem(storageKey)
    } else {
      store.setItem(storageKey, String(lineId))
    }
  }

  function applyStations (requestedLineId, stations) {
    // 默认线请求（null）返回的是后端选出的第一条运行线，activeLineId 必须取节点真实 lineId，
    // 否则「stations 是 L01、activeLineId 却是 null」会让同线判断与录入闸门全部失准
    state.activeLineId = requestedLineId == null
      ? (Array.isArray(stations) && stations[0] ? stations[0].lineId : null)
      : requestedLineId
    state.activeLineNo = stations?.[0]?.lineNo || ''
    state.stations = Array.isArray(stations) ? stations : []
  }

  /** 切换底部导航线路并加载节点；失败时保持原线路节点不变（绝不会停留在错线状态） */
  async function switchLine (lineId) {
    const stations = await fetchStations(lineId)
    applyStations(lineId, stations)
    persistLine(lineId)
    return state.stations
  }

  /**
   * 页面载入时恢复：有持久化的最近线路就恢复它（刷新后的连续性），否则加载默认线。
   * 仅执行一次，重复调用复用同一 Promise。
   */
  function restore () {
    if (restoredPromise) return restoredPromise
    const storedRaw = store.getItem(storageKey)
    const storedId = storedRaw === null || storedRaw === '' ? null : Number(storedRaw)
    restoredPromise = (async () => {
      try {
        // 恢复失败（最近线路已删除等）时回退默认线，不阻断首屏
        await switchLine(Number.isFinite(storedId) ? storedId : null)
      } catch (e) {
        store.removeItem(storageKey)
        await switchLine(null)
      }
      state.ready = true
      return state
    })()
    return restoredPromise
  }

  /**
   * 选中任务：跨线必须先加载目标线路节点再绑定任务；加载失败任务保持未选中，
   * 避免出现「任务在 A 线、节点是 B 线」的任何中间态。
   */
  async function bindTask (task) {
    if (!task || task.id == null || task.lineId == null) {
      throw new Error('任务缺少 id/lineId，无法绑定线路')
    }
    if (state.activeLineId !== task.lineId) {
      await switchLine(task.lineId)
    }
    state.currentTask = task
    state.capsule.plate = task.plateNo || ''
    state.capsule.seq = task.currentSeqNo || 0
    return state
  }

  /**
   * 用最新任务详情对齐当前任务（切换任务 / 提交推进 / 定时刷新 / 转线后调用）。
   * - 任务线路变化（跨线转线）：先加载新线路节点；
   * - 任务已结束（PASS/FAIL）或已从在检队列消失（ended=true）：离开在检队列，
   *   按「保留最近线路」保留节点；胶囊停在末位工位展示完成位置，清空当前任务；
   * - 同线：仅同步胶囊到最新工位。
   * @returns {{ended: boolean, lineChanged: boolean}}
   */
  async function resyncTask (task) {
    if (!task) {
      clearCurrentTask()
      return { ended: true, lineChanged: false }
    }
    const finished = task.status === 'PASS' || task.status === 'FAIL'
    let lineChanged = false
    if (state.activeLineId !== task.lineId) {
      await switchLine(task.lineId)
      lineChanged = true
    }
    if (finished) {
      // 胶囊停在末位（seq 为最后工位号），不随 currentTask 清空，页面可见车辆走完流水线
      state.capsule.plate = task.plateNo || state.capsule.plate
      state.capsule.seq = task.currentSeqNo || state.capsule.seq
      state.currentTask = null
      return { ended: true, lineChanged }
    }
    state.currentTask = task
    state.capsule.plate = task.plateNo || ''
    state.capsule.seq = task.currentSeqNo || 0
    return { ended: false, lineChanged }
  }

  /**
   * 任务消失 / 结束后清空当前任务与胶囊，但保留最近线路节点
   * （执行者决策：无当前任务时保留最近线路，保证刷新后的连续性）。
   */
  function clearCurrentTask () {
    state.currentTask = null
    state.capsule.plate = ''
    state.capsule.seq = 0
  }

  /** 重新加载当前线路节点（排队徽章 / 工位状态刷新）；不改变 activeLineId */
  async function reloadStations () {
    const stations = await fetchStations(state.activeLineId)
    applyStations(state.activeLineId, stations)
    return state.stations
  }

  /**
   * 节点录入闸门：只允许录入【当前线路】上车辆【当前所在】工位。
   * @returns {{allowed: true} | {allowed: false, reason: string}}
   */
  function inspectNode (node) {
    if (!node || node.id == null) return { allowed: false, reason: 'NOT_CURRENT' }
    if (!state.currentTask) return { allowed: false, reason: 'NO_TASK' }
    if (state.activeLineId !== node.lineId || state.currentTask.lineId !== node.lineId) {
      return { allowed: false, reason: 'OTHER_LINE' }
    }
    if (node.id !== state.currentTask.currentStationId) {
      return { allowed: false, reason: 'NOT_CURRENT' }
    }
    return { allowed: true }
  }

  /** 底部节点点击：带 nonce 投递给 FlowView（同节点重复点击也能重新触发 watch） */
  function requestNode (node) {
    state.pendingNonce += 1
    state.pendingNode = { node, nonce: state.pendingNonce }
    return state.pendingNode
  }

  function clearPendingNode () {
    state.pendingNode = null
  }

  /** 通知 FlowView 定时刷新到点：重拉当前任务详情（由页面执行，本模块不耦合接口） */
  function kickResync () {
    state.resyncNonce += 1
    return state.resyncNonce
  }

  return {
    state,
    get ready () {
      return state.ready
    },
    restored: () => restoredPromise || Promise.resolve(state),
    restore,
    switchLine,
    bindTask,
    resyncTask,
    clearCurrentTask,
    reloadStations,
    inspectNode,
    requestNode,
    clearPendingNode,
    kickResync,
    persistLine
  }
}
