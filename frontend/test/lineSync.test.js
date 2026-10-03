import { test, beforeEach } from 'node:test'
import assert from 'node:assert/strict'

import {
  createLineSync,
  LAST_LINE_STORAGE_KEY,
  NODE_REJECT_MESSAGES
} from '../src/composables/lineSync.js'

/**
 * 底部流水线导航「任务线路同步」前端交互测试。
 *
 * 覆盖缺陷场景：多运行线路下底部导航始终是第一条线，选中其他线路任务后
 * 胶囊只移动序号、节点/录入弹窗仍是错线工位，提交报「工位不属于任务」。
 *
 * 用桩 fetchStations 记录每次请求的 lineId，校验状态机的同步不变量：
 * stations 与 activeLineId 始终同线、当前任务与其节点始终同线。
 */

function memStore () {
  const m = new Map()
  return {
    getItem: (k) => (m.has(k) ? m.get(k) : null),
    setItem: (k, v) => m.set(k, String(v)),
    removeItem: (k) => m.delete(k),
    _map: m
  }
}

const LINE_1 = 1
const LINE_2 = 2

function station (lineId, seqNo, overrides = {}) {
  return {
    id: lineId * 100 + seqNo,
    lineId,
    lineNo: 'L0' + lineId,
    stationType: 'S' + seqNo,
    stationName: '工位' + seqNo,
    seqNo,
    status: 'IDLE',
    statusName: '空闲',
    queueCount: 0,
    ...overrides
  }
}

function lineStations (lineId, count = 7) {
  return Array.from({ length: count }, (_, i) => station(lineId, i + 1))
}

function makeFetch (storeMap) {
  const calls = []
  const fetchStations = async (lineId) => {
    // null/undefined = 未指定线路（axios 会跳过该参数），后端回默认（第一条运行）线
    const requested = lineId == null ? null : lineId
    calls.push(requested)
    if (requested === null) {
      return storeMap.get(LINE_1)
    }
    if (!storeMap.has(requested)) {
      const e = new Error('检测线不存在：id=' + requested)
      e.status = 400
      throw e
    }
    return storeMap.get(requested)
  }
  return { fetchStations, calls }
}

function task (id, lineId, currentSeqNo = 1, overrides = {}) {
  return {
    id,
    taskNo: 'T' + id,
    lineId,
    plateNo: '京A·T' + id,
    currentSeqNo,
    currentStationId: lineId * 100 + currentSeqNo,
    status: 'CHECKING',
    ...overrides
  }
}

function harness () {
  const storeMap = new Map([
    [LINE_1, lineStations(LINE_1)],
    [LINE_2, lineStations(LINE_2)]
  ])
  const storage = memStore()
  const f = makeFetch(storeMap)
  const sync = createLineSync({ fetchStations: f.fetchStations, storage })
  return { sync, storage, calls: f.calls, storeMap }
}

beforeEach(() => {
  // 隔离：不依赖真实 localStorage
})

test('首次进入无最近线路：加载默认（第一条运行）线，单线路使用方式不变', async () => {
  const { sync, calls } = harness()
  const s = await sync.restore()

  assert.equal(s.activeLineId, LINE_1)
  assert.equal(s.stations.length, 7)
  assert.ok(s.stations.every((n) => n.lineId === LINE_1))
  assert.deepEqual(calls, [null])
  assert.equal(s.ready, true)
})

test('刷新页面后恢复最近线路而非默认线，保持页面连续性', async () => {
  const storage = memStore()
  storage.setItem(LAST_LINE_STORAGE_KEY, String(LINE_2))
  const storeMap = new Map([
    [LINE_1, lineStations(LINE_1)],
    [LINE_2, lineStations(LINE_2)]
  ])
  const f = makeFetch(storeMap)
  const sync = createLineSync({ fetchStations: f.fetchStations, storage })

  const s = await sync.restore()
  assert.equal(s.activeLineId, LINE_2)
  assert.ok(s.stations.every((n) => n.lineId === LINE_2))
  assert.deepEqual(f.calls, [LINE_2])
})

test('选中其他线路的任务：先加载其所属线路节点再绑定，胶囊序号对齐', async () => {
  const { sync, calls } = harness()
  await sync.restore()
  assert.equal(sync.state.activeLineId, LINE_1)

  const t2 = task(77, LINE_2, 3)
  await sync.bindTask(t2)

  assert.equal(sync.state.activeLineId, LINE_2)
  assert.deepEqual(calls, [null, LINE_2])
  assert.ok(sync.state.stations.every((n) => n.lineId === LINE_2))
  assert.equal(sync.state.currentTask.id, 77)
  assert.equal(sync.state.capsule.plate, '京A·T77')
  assert.equal(sync.state.capsule.seq, 3)
})

test('切到任务所属线路后，最近线路写入持久化存储', async () => {
  const { sync, storage } = harness()
  await sync.restore()
  await sync.bindTask(task(77, LINE_2, 3))
  assert.equal(storage.getItem(LAST_LINE_STORAGE_KEY), String(LINE_2))
})

test('同线选择/重选任务不重复请求节点，只同步胶囊', async () => {
  const { sync, calls } = harness()
  await sync.restore()
  await sync.bindTask(task(1, LINE_1, 1))
  await sync.bindTask(task(2, LINE_1, 4))

  assert.deepEqual(calls, [null])
  assert.equal(sync.state.currentTask.id, 2)
  assert.equal(sync.state.capsule.seq, 4)
})

test('绑定任务时线路节点加载失败：任务不绑定、节点保持原线，杜绝错线中间态', async () => {
  const { sync, calls } = harness()
  await sync.restore()
  const before = sync.state.stations

  await assert.rejects(() => sync.bindTask(task(9, 99, 1)), /检测线不存在/)
  assert.equal(sync.state.activeLineId, LINE_1)
  assert.equal(sync.state.currentTask, null)
  assert.strictEqual(sync.state.stations, before)
  assert.deepEqual(calls, [null, 99])
})

test('定时刷新：同线推进时胶囊跟进最新工位，线路节点不重置', async () => {
  const { sync, calls } = harness()
  await sync.restore()
  await sync.bindTask(task(77, LINE_2, 2))
  calls.length = 0

  const moved = task(77, LINE_2, 5)
  const r = await sync.resyncTask(moved)
  assert.deepEqual(r, { ended: false, lineChanged: false })
  assert.equal(sync.state.capsule.seq, 5)
  assert.equal(sync.state.activeLineId, LINE_2)
  assert.deepEqual(calls, []) // 同线不重拉节点（徽章由 reloadStations 单独刷新）
})

test('定时刷新：任务经跨线转线落到新线路时，节点跟随切换到新线路', async () => {
  const { sync, calls } = harness()
  await sync.restore()
  await sync.bindTask(task(77, LINE_1, 3))
  calls.length = 0

  // 转线后任务 lineId 变为 L02（未完成环节从目标线同 seqNo 继续）
  const transferred = task(77, LINE_2, 3)
  const r = await sync.resyncTask(transferred)
  assert.deepEqual(r, { ended: false, lineChanged: true })
  assert.equal(sync.state.activeLineId, LINE_2)
  assert.ok(sync.state.stations.every((n) => n.lineId === LINE_2))
  assert.equal(sync.state.currentTask.lineId, LINE_2)
  assert.deepEqual(calls, [LINE_2])
})

test('任务结束（PASS/FAIL）：离开在检队列、清空当前任务，但保留最近线路与末位胶囊', async () => {
  const { sync } = harness()
  await sync.restore()
  await sync.bindTask(task(77, LINE_2, 7))

  const finished = task(77, LINE_2, 7, { status: 'PASS', statusName: '合格' })
  const r = await sync.resyncTask(finished)
  assert.deepEqual(r, { ended: true, lineChanged: false })
  assert.equal(sync.state.currentTask, null)
  assert.equal(sync.state.activeLineId, LINE_2)
  assert.ok(sync.state.stations.every((n) => n.lineId === LINE_2))
  assert.equal(sync.state.capsule.seq, 7)
  assert.equal(sync.state.capsule.plate, '京A·T77')
})

test('resyncTask(null)（任务从在检队列消失）：清空任务但保留最近线路', async () => {
  const { sync } = harness()
  await sync.restore()
  await sync.bindTask(task(77, LINE_2, 2))
  const r = await sync.resyncTask(null)
  assert.equal(r.ended, true)
  assert.equal(sync.state.currentTask, null)
  assert.equal(sync.state.activeLineId, LINE_2)
})

test('节点录入闸门：无任务点击 → NO_TASK', () => {
  const { sync } = harness()
  assert.equal(sync.inspectNode(station(LINE_1, 1)).reason, 'NO_TASK')
})

test('节点录入闸门：当前任务在 L02 时点击 L01 节点 → OTHER_LINE，弹窗不会错线', async () => {
  const { sync } = harness()
  await sync.restore()
  await sync.bindTask(task(77, LINE_2, 3))

  const l1Node = station(LINE_1, 3)
  const v = sync.inspectNode(l1Node)
  assert.equal(v.allowed, false)
  assert.equal(v.reason, 'OTHER_LINE')
  assert.match(NODE_REJECT_MESSAGES.OTHER_LINE, /其他检测线/)
})

test('节点录入闸门：同线但非当前工位（前序/后续）→ NOT_CURRENT，必须按顺序流转', async () => {
  const { sync } = harness()
  await sync.restore()
  await sync.bindTask(task(77, LINE_2, 3))

  assert.equal(sync.inspectNode(station(LINE_2, 2)).reason, 'NOT_CURRENT')
  assert.equal(sync.inspectNode(station(LINE_2, 4)).reason, 'NOT_CURRENT')
  // 当前工位才放行
  assert.deepEqual(sync.inspectNode(station(LINE_2, 3, { id: LINE_2 * 100 + 3 })), { allowed: true })
})

test('节点点击投递带递增 nonce：同一节点重复点击也能重新触发录入弹窗', () => {
  const { sync } = harness()
  const node = station(LINE_1, 1)
  const p1 = sync.requestNode(node)
  const p2 = sync.requestNode(node)
  assert.equal(p1.nonce, 1)
  assert.equal(p2.nonce, 2)
  sync.clearPendingNode()
  assert.equal(sync.state.pendingNode, null)
  // nonce 单调递增，清空 pendingNode 不回退
  assert.equal(sync.requestNode(node).nonce, 3)
})

test('定时刷新 tick：kickResync 单调递增，驱动页面重拉当前任务', () => {
  const { sync } = harness()
  assert.equal(sync.kickResync(), 1)
  assert.equal(sync.kickResync(), 2)
})

test('持久化的最近线路已不存在：回退默认线并清掉失效存储，不阻断首屏', async () => {
  const storage = memStore()
  storage.setItem(LAST_LINE_STORAGE_KEY, '404')
  const storeMap = new Map([[LINE_1, lineStations(LINE_1)]])
  const f = makeFetch(storeMap)
  const sync = createLineSync({ fetchStations: f.fetchStations, storage })

  const s = await sync.restore()
  assert.equal(s.activeLineId, LINE_1)
  assert.deepEqual(f.calls, [404, null])
  assert.equal(storage.getItem(LAST_LINE_STORAGE_KEY), null)
})

test('restore 幂等：多次调用复用同一次恢复，不重复请求', async () => {
  const { sync, calls } = harness()
  const [a, b] = await Promise.all([sync.restore(), sync.restore()])
  assert.strictEqual(a, b)
  assert.deepEqual(calls, [null])
  const c = await sync.restored()
  assert.strictEqual(c, a)
})

test('localStorage 不可用时退化为内存存储，线路同步仍可工作', async () => {
  const broken = {
    getItem () { throw new Error('denied') },
    setItem () { throw new Error('denied') },
    removeItem () { throw new Error('denied') }
  }
  const storeMap = new Map([[LINE_1, lineStations(LINE_1)], [LINE_2, lineStations(LINE_2)]])
  const f = makeFetch(storeMap)
  const sync = createLineSync({ fetchStations: f.fetchStations, storage: broken })
  await sync.restore()
  await sync.bindTask(task(5, LINE_2, 1))
  assert.equal(sync.state.activeLineId, LINE_2)
})
