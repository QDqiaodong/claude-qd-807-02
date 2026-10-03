<template>
  <div class="vc-app">
    <header class="vc-top">
      <div class="vc-brand-mark">JC</div>
      <div>
        <div class="vc-brand-title">机动车检测站 · 工位与检测报告管理</div>
        <div class="vc-brand-sub">检测线工位流水 · 检测报告 · 引车员排班 · 设备标定</div>
      </div>
      <div class="vc-top-kpis">
        <div class="vc-kpi">在检 <b>{{ overview.checkingCount ?? '-' }}</b></div>
        <div class="vc-kpi">报告 <b>{{ overview.reportCount ?? '-' }}</b></div>
        <div class="vc-kpi">不合格 <b>{{ overview.failReportCount ?? '-' }}</b></div>
        <div class="vc-kpi" :class="{ danger: (overview.overdueDeviceCount || 0) > 0 }">
          超期设备 <b>{{ overview.overdueDeviceCount ?? '-' }}</b>
        </div>
        <div class="vc-kpi">Redis 台次 <b>{{ overview.redisCheckedCount ?? '-' }}</b></div>
      </div>
    </header>

    <main class="vc-main">
      <component :is="activeView" />
    </main>

    <!-- 底部固定流水线进度条导航：常驻可见，节点始终属于当前任务所在线路 -->
    <footer class="vc-pipeline">
      <div class="vc-pipe-track">
        <div class="vc-pipe-line">
          <div class="vc-pipe-line-fill" :style="{ width: progressWidth }"></div>
        </div>

        <el-tooltip
          v-if="state.capsule.plate"
          :content="'当前车辆 ' + state.capsule.plate + ' · 第 ' + state.capsule.seq + ' 工位'"
          placement="top"
        >
          <div class="vc-pipe-capsule" :style="{ left: capsuleLeft }">
            {{ state.capsule.plate }}
          </div>
        </el-tooltip>
        <span v-else class="vc-pipe-capsule-idle">未选择当前车辆</span>

        <div
          v-for="(node, index) in state.stations"
          :key="node.id"
          class="vc-pipe-node"
          :class="{
            done: state.capsule.seq > index + 1,
            current: state.capsule.seq === index + 1,
            fault: node.status === 'FAULT'
          }"
          @click="onNodeClick(node)"
        >
          <span class="vc-node-badge" :class="{ zero: !node.queueCount }">{{ node.queueCount }}</span>
          <span class="vc-node-dot">{{ index + 1 }}</span>
          <span class="vc-node-name">{{ node.stationName }}</span>
          <span class="vc-node-sub">{{ node.statusName }}</span>
        </div>
      </div>

      <div class="vc-pipe-meta">
        <el-tooltip
          :content="state.currentTask
            ? '导航跟随当前任务「' + state.currentTask.plateNo + '」所在线路'
            : '无在检任务，保留最近查看线路；选择任务后自动切换到其所属线路'"
          placement="top"
        >
          <span class="vc-pipe-linetag">
            导航线路 <b>{{ state.activeLineNo || '默认线' }}</b>
            <i v-if="!state.currentTask" class="vc-pipe-linetag-sub">最近线路</i>
          </span>
        </el-tooltip>
      </div>

      <div class="vc-pipe-views">
        <span
          v-for="v in views"
          :key="v.path"
          class="vc-view-chip"
          :class="{ active: currentPath === v.path }"
          @click="go(v.path)"
        >
          {{ v.label }}
          <i v-if="v.path === '/recovery' && recoveryCount > 0" class="vc-view-badge">
            {{ recoveryCount }}
          </i>
        </span>
      </div>
    </footer>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, provide, reactive, ref } from 'vue'
import router from './router'
import FlowView from './views/FlowView.vue'
import RecoveryView from './views/RecoveryView.vue'
import ReportView from './views/ReportView.vue'
import ScheduleView from './views/ScheduleView.vue'
import DeviceView from './views/DeviceView.vue'
import { fetchPipeline, fetchOverview, fetchRecoveryBoard } from './api'
import { createLineSync } from './composables/lineSync'

const VIEW_MAP = {
  '/flow': FlowView,
  '/recovery': RecoveryView,
  '/report': ReportView,
  '/schedule': ScheduleView,
  '/device': DeviceView
}

const currentPath = ref('/flow')
const activeView = computed(() => VIEW_MAP[currentPath.value] || FlowView)

const views = [
  { path: '/flow', label: '工位流水线' },
  { path: '/recovery', label: '跨线恢复' },
  { path: '/report', label: '检测报告' },
  { path: '/schedule', label: '引车员排班' },
  { path: '/device', label: '设备标定' }
]

const overview = ref({})
const recoveryCount = ref(0)
let pollTimer = null

// 底部导航线路同步状态机：选中任务加载其线路节点；无任务保留最近线路；
// 定时刷新 / 任务结束 / 跨线转线后节点都不错配；节点点击经闸门只录入当前线工位。
// wrapState 传 reactive：状态机内部写入直接驱动模板更新
const lineSync = createLineSync({ fetchStations: fetchPipeline, wrapState: reactive })
const state = lineSync.state

provide('pipeline', {
  state,
  // FlowView 等页面只通过下列方法操作导航，避免直接改状态破坏不变量
  restore: lineSync.restore,
  restored: lineSync.restored,
  switchLine: lineSync.switchLine,
  bindTask: lineSync.bindTask,
  resyncTask: lineSync.resyncTask,
  clearCurrentTask: lineSync.clearCurrentTask,
  reloadStations: lineSync.reloadStations,
  inspectNode: lineSync.inspectNode,
  requestNode: lineSync.requestNode,
  clearPendingNode: lineSync.clearPendingNode,
  kickResync: lineSync.kickResync,
  reloadAll: load
})

const capsuleLeft = computed(() => {
  const total = state.stations.length || 7
  const idx = Math.min(Math.max(state.capsule.seq - 1, 0), total - 1)
  return ((idx + 0.5) * 100) / total + '%'
})

const progressWidth = computed(() => {
  const total = state.stations.length || 7
  if (!state.capsule.seq || total < 2) return '0%'
  return ((state.capsule.seq - 1) * 100) / (total - 1) + '%'
})

router.afterEach((to) => {
  currentPath.value = to.path
})

function go (path) {
  router.push(path)
}

function onNodeClick (node) {
  // 投递后延迟一拍置值，保证同一节点重复点击、跨页跳转挂载 FlowView 时 watch 都能触发
  lineSync.clearPendingNode()
  setTimeout(() => lineSync.requestNode(node), 30)
  if (currentPath.value !== '/flow') {
    router.push('/flow')
  }
}

async function loadStations () {
  // 只重刷当前线路节点（排队徽章 / 工位状态），activeLineId 与节点始终同线
  try {
    await lineSync.reloadStations()
  } catch (e) {
    // 当前线路被停用/删除等异常：节点保持上一次成功快照，不清空成别的线路
  }
}

async function loadOverview () {
  try {
    overview.value = await fetchOverview()
  } catch (e) {
    overview.value = {}
  }
}

async function loadRecovery () {
  try {
    const board = await fetchRecoveryBoard()
    recoveryCount.value = (board.affectedTasks || []).filter((t) => !t.resolved).length
  } catch (e) {
    recoveryCount.value = 0
  }
}

async function load () {
  await Promise.all([loadStations(), loadOverview(), loadRecovery()])
  // 定时刷新到点：通知 FlowView 按最新任务对齐胶囊 / 线路 / 结束状态
  lineSync.kickResync()
}

onMounted(async () => {
  router.push(window.location.pathname || '/flow')
  // 先恢复最近线路（无则默认线），FlowView 挂载时 await restored() 再选任务，避免父子挂载竞态
  try {
    await lineSync.restore()
  } catch (e) {
    // 连默认线都不可用（无运行线）时首屏仍渲染，轮询会在恢复后补上节点
  }
  await load()
  // 跨线恢复涉及并发处置：底部工位占用、待处置数量每 8 秒自动刷新
  pollTimer = setInterval(load, 8000)
})

onUnmounted(() => {
  if (pollTimer) clearInterval(pollTimer)
})
</script>

<style scoped>
.vc-pipe-meta {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  padding: 0 14px 0 6px;
}

.vc-pipe-linetag {
  display: inline-flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  padding: 8px 12px;
  border: 1px dashed var(--vc-border);
  border-radius: 8px;
  background: #f4f9fd;
  font-size: 12px;
  color: var(--vc-muted);
  white-space: nowrap;
}

.vc-pipe-linetag b {
  font-size: 14px;
  color: var(--vc-primary);
  letter-spacing: 0.5px;
}

.vc-pipe-linetag-sub {
  font-style: normal;
  font-size: 10px;
  color: #9fb0c2;
}
</style>
