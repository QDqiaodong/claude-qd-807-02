<template>
  <div style="display: grid; gap: 14px;">
    <div class="vc-card">
      <div class="vc-card-title">
        引车员排班格子表
        <span class="vc-muted" style="font-weight: 400; font-size: 12px;">
          纵轴引车员（资质/在岗/排班时段）· 横轴 8:00-18:00 ｜ 点空格派发，点「待派」车牌改派，格子状态以服务端为准
        </span>
        <el-date-picker
          v-model="date"
          size="small"
          type="date"
          value-format="YYYY-MM-DD"
          style="width: 150px; margin-left: auto;"
          @change="load"
        />
        <el-button size="small" @click="load">刷新</el-button>
      </div>

      <div class="vc-legend">
        <span class="lg lg-empty">待派空格</span>
        <span class="lg lg-waiting">待派（可改派）</span>
        <span class="lg lg-checking">在检/复检</span>
        <span class="lg lg-pass">合格</span>
        <span class="lg lg-fail">不合格</span>
        <span class="lg lg-off">不在班/不在岗</span>
      </div>

      <div class="vc-sched">
        <div class="vc-sched-corner">引车员 / 时段</div>
        <div v-for="h in HOURS" :key="'h' + h" class="vc-sched-col-head">{{ h }}:00</div>

        <template v-for="row in rows" :key="row.driverId">
          <div class="vc-sched-row-head">
            <div class="vc-sched-name">
              {{ row.driverName }}
              <el-tag size="small" :type="certTagType(row.certLevel)">{{ row.certLevel }} 资质</el-tag>
            </div>
            <div class="vc-sched-meta">
              证号 {{ row.certNo }} ｜ 排班 {{ shiftLabel(row.shiftHours) }} ｜ 已检 {{ row.checkedCount }} 台
            </div>
            <el-tag v-if="row.status === 'ON_DUTY'" size="small" type="success">在岗</el-tag>
            <el-tag v-else size="small" type="info">不在岗</el-tag>
          </div>
          <div
            v-for="cell in row.cells"
            :key="row.driverId + '-' + cell.hour"
            class="vc-sched-cell"
            :class="cellClass(row, cell)"
            @click="onCell(row, cell)"
          >
            <template v-if="cell.taskId">
              <div class="vc-cell-plate">{{ cell.plateNo }}</div>
              <div class="vc-cell-meta">{{ cell.lineNo }} · {{ cell.stationName || cell.statusName }}</div>
              <el-tag size="small" :type="tagType(cell)">{{ stateLabel(cell) }}</el-tag>
            </template>
            <span v-else-if="canAssign(row, cell)" class="vc-cell-empty">＋ 待派</span>
            <span v-else class="vc-cell-empty vc-cell-lock">—</span>
          </div>
        </template>
      </div>
    </div>

    <div class="vc-card">
      <div class="vc-card-title">按引车员统计当日台次（未结束任务有效占格）</div>
      <el-table :data="driverStats" size="small">
        <el-table-column prop="driverName" label="引车员" width="160" />
        <el-table-column prop="taskCount" label="当日未结束台次" width="160" />
        <el-table-column label="负载">
          <template #default="{ row }">
            <el-progress
              :percentage="Math.min(100, row.taskCount * 20)"
              :stroke-width="12"
              :color="row.taskCount >= 4 ? '#d8342c' : '#01579b'"
            />
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 派发 -->
    <el-dialog v-model="assignVisible" title="派发到检测线起点" width="480px">
      <el-alert type="info" :closable="false" show-icon style="margin-bottom: 12px;">
        引车员 {{ activeDriver?.driverName }}（{{ activeDriver?.certLevel }}）｜ 预定时段
        {{ activeCell?.hour }}:00 ｜ 任务、格子、队列起点一次绑定
      </el-alert>
      <el-form label-width="100px">
        <el-form-item label="待检车辆">
          <el-select
            v-model="assignForm.vehicleId"
            filterable
            style="width: 100%;"
            @change="precheck"
          >
            <el-option
              v-for="v in assignable"
              :key="v.id"
              :label="v.plateNo + ' · ' + v.ownerName"
              :value="v.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="检测线">
          <el-select
            v-model="assignForm.lineId"
            style="width: 100%;"
            @change="precheck"
          >
            <el-option
              v-for="l in runningLines"
              :key="l.id"
              :label="l.lineNo + ' · ' + l.lineName"
              :value="l.id"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <el-alert
        v-if="blockReasons.length"
        type="error"
        :closable="false"
        show-icon
        title="当前选择存在阻断原因"
        style="margin-bottom: 8px;"
      >
        <div v-for="(r, i) in blockReasons" :key="i">· {{ r }}</div>
      </el-alert>
      <el-alert v-else-if="prechecked" type="success" :closable="false" show-icon title="资格校验通过，可以派发" />
      <template #footer>
        <el-button @click="assignVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="submitting"
          :disabled="!!blockReasons.length || !assignForm.vehicleId || !assignForm.lineId"
          @click="submitAssign"
        >派 发</el-button>
      </template>
    </el-dialog>

    <!-- 改派 -->
    <el-dialog v-model="reassignVisible" title="改派（仅未开始检测任务）" width="480px">
      <el-alert type="info" :closable="false" show-icon style="margin-bottom: 12px;">
        当前任务 {{ activeCell?.taskNo }} ｜ 车辆 {{ activeCell?.plateNo }} ｜
        当前 {{ activeCell?.lineNo }} · {{ activeCell?.stationName }}
      </el-alert>
      <el-alert
        v-if="activeCell && activeCell.blockReasons && activeCell.blockReasons.length"
        type="warning"
        :closable="false"
        show-icon
        style="margin-bottom: 12px;"
      >
        <div v-for="(r, i) in activeCell.blockReasons" :key="i">· {{ r }}</div>
      </el-alert>
      <el-form label-width="100px">
        <el-form-item label="改派给">
          <el-select
            v-model="reassignForm.driverId"
            style="width: 100%;"
            :disabled="!canReassign"
            @change="precheckReassign"
          >
            <el-option
              v-for="d in reassignableDrivers"
              :key="d.id"
              :label="d.driverName + '（' + d.certLevel + '）'"
              :value="d.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="时段">
          <el-select
            v-model="reassignForm.hour"
            style="width: 100%;"
            :disabled="!canReassign"
            @change="precheckReassign"
          >
            <el-option v-for="h in HOURS" :key="h" :label="h + ':00'" :value="h" />
          </el-select>
        </el-form-item>
      </el-form>
      <el-alert
        v-if="reassignBlockReasons.length"
        type="error"
        :closable="false"
        show-icon
        title="目标格阻断原因"
        style="margin-bottom: 8px;"
      >
        <div v-for="(r, i) in reassignBlockReasons" :key="i">· {{ r }}</div>
      </el-alert>
      <template #footer>
        <el-button @click="reassignVisible = false">取消</el-button>
        <el-button
          v-if="canReassign"
          type="primary"
          :loading="submitting"
          :disabled="!!reassignBlockReasons.length"
          @click="submitReassign"
        >改 派</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  fetchSchedule,
  fetchDriverStats,
  fetchDrivers,
  fetchLines,
  fetchAssignableVehicles,
  checkDispatch,
  checkReassign,
  dispatchTask,
  reassignTaskV2
} from '../api'

const HOURS = [8, 9, 10, 11, 12, 13, 14, 15, 16, 17]

const date = ref(new Date().toISOString().slice(0, 10))
const rows = ref([])
const driverStats = ref([])
const drivers = ref([])
const lines = ref([])
const assignable = ref([])

const runningLines = computed(() => lines.value.filter((l) => l.status === 'RUNNING'))
// 改派下拉只列在岗引车员（目标必须资质有效且在岗），但保留当前引车员用于展示
const reassignableDrivers = computed(() =>
  drivers.value.filter((d) => d.status === 'ON_DUTY'))

const assignVisible = ref(false)
const reassignVisible = ref(false)
const activeDriver = ref(null)
const activeCell = ref(null)
const submitting = ref(false)
const prechecked = ref(false)
const blockReasons = ref([])
const reassignBlockReasons = ref([])
const assignForm = reactive({ vehicleId: null, lineId: null })
const reassignForm = reactive({ driverId: null, hour: 9 })

const canReassign = computed(() => activeCell.value?.cellState === 'WAITING')

function shiftLabel (shiftHours) {
  if (!shiftHours) return '未排班'
  const parts = String(shiftHours).split(',')
  return parts[0] + ':00-' + (Number(parts[parts.length - 1]) + 1) + ':00'
}

function inShift (shiftHours, hour) {
  return String(shiftHours || '').split(',').map(Number).includes(hour)
}

function certTagType (level) {
  if (level === 'A1' || level === 'A2') return 'danger'
  if (level === 'B2') return 'warning'
  return 'info'
}

function stateLabel (cell) {
  switch (cell.cellState) {
    case 'WAITING': return '待派'
    case 'CHECKING': return '在检'
    case 'RETEST': return '复检'
    case 'PASS': return '合格'
    case 'FAIL': return '不合格'
    default: return cell.statusName
  }
}

function tagType (cell) {
  if (cell.cellState === 'PASS') return 'success'
  if (cell.cellState === 'FAIL') return 'danger'
  if (cell.cellState === 'RETEST') return 'warning'
  if (cell.cellState === 'WAITING') return 'primary'
  return 'info'
}

function canAssign (row, cell) {
  return row.status === 'ON_DUTY' && cell.inShift && cell.onDuty && !cell.taskId
}

function cellClass (row, cell) {
  return {
    off: !canAssign(row, cell) && !cell.taskId,
    filled: !!cell.taskId,
    waiting: cell.cellState === 'WAITING',
    checking: cell.cellState === 'CHECKING' || cell.cellState === 'RETEST',
    pass: cell.cellState === 'PASS',
    fail: cell.cellState === 'FAIL'
  }
}

async function load () {
  const [schedule, stats, driverList, lineList, vehicles] = await Promise.all([
    fetchSchedule(date.value),
    fetchDriverStats(date.value),
    fetchDrivers(),
    fetchLines(),
    fetchAssignableVehicles(date.value)
  ])
  rows.value = schedule
  driverStats.value = stats
  drivers.value = driverList
  lines.value = lineList
  assignable.value = vehicles
}

function onCell (row, cell) {
  activeDriver.value = row
  activeCell.value = cell
  blockReasons.value = []
  reassignBlockReasons.value = []
  prechecked.value = false
  if (cell.taskId) {
    reassignForm.driverId = row.driverId
    reassignForm.hour = cell.hour
    reassignVisible.value = true
    if (canReassign.value) precheckReassign()
  } else if (canAssign(row, cell)) {
    assignForm.vehicleId = assignable.value[0]?.id ?? null
    assignForm.lineId = runningLines.value[0]?.id ?? lines.value[0]?.id ?? null
    assignVisible.value = true
    precheck()
  } else {
    ElMessage.warning((cell.blockReasons && cell.blockReasons[0]) || '该格不可派发')
  }
}

async function precheck () {
  prechecked.value = false
  if (!assignForm.vehicleId || !assignForm.lineId || !activeCell.value) return
  try {
    const res = await checkDispatch({
      vehicleId: assignForm.vehicleId,
      lineId: assignForm.lineId,
      driverId: activeDriver.value.driverId,
      scheduleHour: activeCell.value.hour,
      checkDate: date.value
    })
    blockReasons.value = res.blockReasons || []
    prechecked.value = true
  } catch (e) {
    blockReasons.value = [e.message]
  }
}

async function precheckReassign () {
  reassignBlockReasons.value = []
  if (!activeCell.value || !reassignForm.driverId || reassignForm.hour == null) return
  try {
    const res = await checkReassign(
      activeCell.value.taskId, reassignForm.driverId, reassignForm.hour)
    reassignBlockReasons.value = res.blockReasons || []
  } catch (e) {
    reassignBlockReasons.value = [e.message]
  }
}

async function submitAssign () {
  submitting.value = true
  try {
    const result = await dispatchTask({
      vehicleId: assignForm.vehicleId,
      lineId: assignForm.lineId,
      driverId: activeDriver.value.driverId,
      scheduleHour: activeCell.value.hour,
      checkDate: date.value
    })
    assignVisible.value = false
    ElMessage.success('派发成功：' + result.cell.taskNo)
    await load()
  } catch (e) {
    if (e.status === 409) {
      // 派发同格冲突时 data 为 null；并发改派冲突时 data 带任务当前归属
      ElMessage.warning(e.message)
      assignVisible.value = false
      await load()
    } else {
      ElMessage.error(e.message)
      blockReasons.value = [e.message]
    }
  } finally {
    submitting.value = false
  }
}

async function submitReassign () {
  submitting.value = true
  try {
    await reassignTaskV2(activeCell.value.taskId, {
      driverId: reassignForm.driverId,
      scheduleHour: reassignForm.hour
    })
    reassignVisible.value = false
    ElMessage.success('改派成功：原格已释放，新格已占用')
    await load()
  } catch (e) {
    if (e.status === 409) {
      // 并发改派 / 重复点击 / 已开始结束：本次没生效；有当前归属时一并提示，按服务端状态刷新
      const cur = e.data?.cell
      await ElMessageBox.alert(
        e.message + (cur ? `\n当前归属：${cur.plateNo || cur.taskNo} → `
          + (cur.hourLabel || '') + ' 时段' : ''),
        '该任务当前不可改派', { type: 'warning' })
      reassignVisible.value = false
      await load()
    } else {
      ElMessage.error(e.message)
    }
  } finally {
    submitting.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.vc-legend {
  display: flex;
  gap: 10px;
  margin: 8px 0 10px;
  font-size: 12px;
}

.lg {
  padding: 2px 10px;
  border-radius: 10px;
  border: 1px solid var(--vc-border);
}

.lg-empty { background: #fff; }
.lg-waiting { background: #e3f0fb; color: #01579b; }
.lg-checking { background: #e8f5e9; color: #2e7d32; }
.lg-pass { background: #e8f5e9; color: #1b5e20; }
.lg-fail { background: #fdecea; color: #b71c1c; }
.lg-off { background: #f5f7f9; color: #90a4ae; }

.vc-sched {
  display: grid;
  grid-template-columns: 210px repeat(10, 1fr);
  gap: 4px;
}

.vc-sched-corner,
.vc-sched-col-head {
  background: #eaf2fa;
  color: var(--vc-primary-deep);
  font-size: 12px;
  font-weight: 700;
  text-align: center;
  padding: 6px 4px;
  border-radius: 4px;
}

.vc-sched-corner {
  text-align: left;
  padding-left: 10px;
}

.vc-sched-row-head {
  border: 1px solid var(--vc-border);
  border-radius: 6px;
  padding: 8px 10px;
  background: #fafcfe;
  display: flex;
  flex-direction: column;
  gap: 2px;
  justify-content: center;
}

.vc-sched-name {
  font-size: 14px;
  font-weight: 700;
  color: var(--vc-primary-deep);
  display: flex;
  align-items: center;
  gap: 6px;
}

.vc-sched-meta {
  font-size: 11px;
  color: var(--vc-muted);
}

.vc-sched-cell {
  min-height: 62px;
  border: 1px dashed #d3dfe9;
  border-radius: 6px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  cursor: pointer;
  background: #fff;
  transition: all 0.18s ease;
  padding: 4px;
}

.vc-sched-cell.off {
  background: repeating-linear-gradient(45deg, #f5f7f9 0 8px, #eef2f6 8px 16px);
  cursor: not-allowed;
}

.vc-sched-cell:hover {
  border-color: var(--vc-primary);
  box-shadow: 0 4px 10px rgba(1, 87, 155, 0.12);
}

.vc-sched-cell.waiting {
  border-style: solid;
  border-color: #7fb3dd;
  background: linear-gradient(180deg, #eef6fd 0%, #dcecf9 100%);
}

.vc-sched-cell.checking {
  border-style: solid;
  border-color: #9ccc9f;
  background: linear-gradient(180deg, #f1f9f1 0%, #e2f2e4 100%);
  cursor: default;
}

.vc-sched-cell.pass {
  border-style: solid;
  border-color: #81c784;
  background: #e8f5e9;
  cursor: default;
}

.vc-sched-cell.fail {
  border-style: solid;
  border-color: #e57373;
  background: #fdecea;
  cursor: default;
}

.vc-cell-plate {
  font-size: 13px;
  font-weight: 700;
  color: var(--vc-primary-deep);
}

.vc-cell-meta {
  font-size: 11px;
  color: var(--vc-muted);
}

.vc-cell-empty {
  color: #7fa6c8;
  font-size: 12px;
}

.vc-cell-lock {
  color: #c2cedb;
}
</style>
