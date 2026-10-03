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

    <!-- 底部固定流水线进度条导航：常驻可见 -->
    <footer class="vc-pipeline">
      <div class="vc-pipe-track">
        <div class="vc-pipe-line">
          <div class="vc-pipe-line-fill" :style="{ width: progressWidth }"></div>
        </div>

        <el-tooltip
          :content="capsule.plate ? '导航线路与当前车辆所属检测线保持一致' : '未选择车辆时保留最近线路'"
          placement="top"
        >
          <span class="vc-pipe-line-tag">{{ pipelineLineNo || '线路' }}</span>
        </el-tooltip>

        <el-tooltip
          v-if="capsule.plate"
          :content="'当前车辆 ' + capsule.plate + ' · 第 ' + capsule.seq + ' 工位'"
          placement="top"
        >
          <div class="vc-pipe-capsule" :style="{ left: capsuleLeft }">
            {{ capsule.plate }}
          </div>
        </el-tooltip>
        <span v-else class="vc-pipe-capsule-idle">未选择当前车辆</span>

        <div
          v-for="(node, index) in stations"
          :key="node.id"
          class="vc-pipe-node"
          :class="{
            done: capsule.seq > index + 1,
            current: capsule.seq === index + 1,
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

const stations = ref([])
const overview = ref({})
const recoveryCount = ref(0)
let pollTimer = null

// 底部导航当前钉住的检测线：null = 默认第一条运行线。
// 决策：没有当前任务时「保留最近线路」——任务结束或清空选中后导航不跳回默认线，
// 定时刷新与手动刷新都停留在最近线路，保证页面连续；全新会话（尚未选过任务）仍是默认线，
// 只有一条运行线时钉住的线就是默认线，单线路使用方式不变。
const pipelineLineId = ref(null)

// 沿流水线移动的「当前车辆」胶囊
const capsule = reactive({ plate: '', seq: 0 })
// 点击流水线节点后交给流水线页面弹出该工位录入表
const activeStation = ref(null)

// 底部导航当前展示线路的线号（取节点自带的 lineNo，无节点时为空）
const pipelineLineNo = computed(() => stations.value[0]?.lineNo || '')

// 单调递增序号：丢弃过期响应——8 秒轮询与选中任务换线并发时，
// 旧线路的响应后返回也不得覆盖新线路节点
let pipelineFetchSeq = 0

async function refreshPipelineStations () {
  const seq = ++pipelineFetchSeq
  const lineId = pipelineLineId.value
  try {
    const result = await fetchPipeline(lineId)
    if (seq === pipelineFetchSeq) stations.value = result
  } catch (e) {
    if (seq !== pipelineFetchSeq) return
    if (lineId != null) {
      // 钉住的线路可能已被删除或接口异常：回退默认线，避免底部导航长期空白
      pipelineLineId.value = null
      try {
        stations.value = await fetchPipeline(null)
      } catch (e2) {
        stations.value = []
      }
    } else {
      stations.value = []
    }
  }
}

provide('pipeline', {
  stations,
  capsule,
  activeStation,
  pipelineLineId,
  moveCapsule (plate, seq) {
    capsule.plate = plate
    capsule.seq = seq
  },
  // 选中任务后由流水线页面调用：把底部导航钉到任务所属线路，节点立即跟随切换
  async setPipelineLine (lineId) {
    if (lineId == null || lineId === pipelineLineId.value) return
    pipelineLineId.value = lineId
    await refreshPipelineStations()
  },
  consumeStation (node) {
    activeStation.value = node
  },
  clearStation () {
    activeStation.value = null
  },
  reloadAll: load
})

const capsuleLeft = computed(() => {
  const total = stations.value.length || 7
  const idx = Math.min(Math.max(capsule.seq - 1, 0), total - 1)
  return ((idx + 0.5) * 100) / total + '%'
})

const progressWidth = computed(() => {
  const total = stations.value.length || 7
  if (!capsule.seq || total < 2) return '0%'
  return ((capsule.seq - 1) * 100) / (total - 1) + '%'
})

router.afterEach((to) => {
  currentPath.value = to.path
})

function go (path) {
  router.push(path)
}

function onNodeClick (node) {
  if (currentPath.value !== '/flow') {
    router.push('/flow')
  }
  consume(node)
}

function consume (node) {
  activeStation.value = null
  setTimeout(() => {
    activeStation.value = node
  }, 30)
}

async function load () {
  // 钉住线路后，8 秒轮询与手动刷新都继续拉该线节点，不会跳回第一条运行线
  await refreshPipelineStations()
  try {
    overview.value = await fetchOverview()
  } catch (e) {
    overview.value = {}
  }
  try {
    const board = await fetchRecoveryBoard()
    recoveryCount.value = (board.affectedTasks || []).filter((t) => !t.resolved).length
  } catch (e) {
    recoveryCount.value = 0
  }
}

onMounted(() => {
  router.push(window.location.pathname || '/flow')
  load()
  // 跨线恢复涉及并发处置：底部工位占用、待处置数量每 8 秒自动刷新
  pollTimer = setInterval(load, 8000)
})

onUnmounted(() => {
  if (pollTimer) clearInterval(pollTimer)
})
</script>
