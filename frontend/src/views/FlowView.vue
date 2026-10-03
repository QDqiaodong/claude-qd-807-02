<template>
  <div class="vc-grid" style="grid-template-columns: 360px 1fr;">
    <!-- 左：当前在检车辆队列 -->
    <section class="vc-card">
      <div class="vc-card-title">
        流水线在检车辆
        <el-button size="small" type="primary" plain @click="openVehicleDialog">车辆建档</el-button>
        <el-button size="small" @click="openTaskDialog">派发任务</el-button>
      </div>

      <el-scrollbar max-height="520px">
        <div
          v-for="t in tasks"
          :key="t.id"
          class="vc-queue-item"
          :class="{ active: current && current.id === t.id }"
          @click="selectTask(t)"
        >
          <div class="vc-queue-plate">{{ t.plateNo }}</div>
          <div class="vc-queue-meta">
            <span>{{ t.taskNo }}</span>
            <span>{{ t.lineNo }} · {{ t.driverName || '未派单' }}</span>
          </div>
          <div class="vc-queue-foot">
            <el-tag size="small" :type="t.status === 'RETEST' ? 'warning' : 'primary'">
              {{ t.statusName }}
            </el-tag>
            <span class="vc-muted">第 {{ t.currentSeqNo }} 工位 · {{ t.currentStationName }}</span>
            <el-button
              v-if="faultStationIds.has(t.currentStationId)"
              size="small" type="danger" plain style="margin-left: auto; padding: 2px 8px;"
              @click.stop="goRecovery"
            >去跨线处置</el-button>
          </div>
        </div>
        <el-empty v-if="!tasks.length" description="暂无在检车辆" :image-size="60" />
      </el-scrollbar>
    </section>

    <!-- 右：当前车辆工位流转 -->
    <section style="display: grid; gap: 14px; grid-template-rows: auto 1fr;">
      <div class="vc-card">
        <div class="vc-card-title">
          当前车辆 · 工位流转
          <el-button size="small" @click="refresh">刷新</el-button>
        </div>

        <div v-if="current" class="vc-flow-head">
          <div>
            <div class="vc-flow-plate">{{ current.plateNo }}</div>
            <div class="vc-muted" style="margin-top: 4px;">
              {{ current.ownerName }} · {{ current.vehicleTypeName }} · 引车员
              {{ current.driverName || '未派单' }} · {{ current.lineNo }}
            </div>
            <div class="vc-muted vc-mono" style="margin-top: 4px;">
              车主身份证（AES 列解密明文）：{{ vehicle?.ownerIdNo || '-' }} ｜ 手机号：{{ vehicle?.ownerPhone || '-' }}
            </div>
          </div>
          <div class="vc-flow-now">
            <div class="vc-flow-now-seq">第 {{ current.currentSeqNo }} 工位</div>
            <div class="vc-flow-now-name">{{ current.currentStationName }}</div>
            <el-button size="small" type="primary" @click="openStation(currentStation)">
              录入检测值
            </el-button>
          </div>
        </div>
        <el-empty v-else description="请在左侧选择一辆在检车辆" :image-size="60" />

        <el-timeline v-if="current" style="margin-top: 14px; padding-left: 4px;">
          <el-timeline-item
            v-for="r in current.records"
            :key="r.id"
            :type="r.result === 'PASS' ? 'success' : 'danger'"
            :timestamp="r.recordTime ? String(r.recordTime).replace('T', ' ') : ''"
            placement="top"
          >
            <b>{{ r.stationName }}</b>
            <el-tag size="small" :type="r.result === 'PASS' ? 'success' : 'danger'" style="margin-left: 6px;">
              {{ r.resultName }}
            </el-tag>
            <div class="vc-muted">{{ r.itemName }} ｜ {{ r.valueText }}</div>
          </el-timeline-item>
          <el-timeline-item v-if="!current.records.length" type="info" placement="top">
            尚未录入任何工位检测值
          </el-timeline-item>
        </el-timeline>
      </div>

      <div class="vc-grid" style="grid-template-columns: 1fr 1fr;">
        <div class="vc-card">
          <div class="vc-card-title">按工位统计通过率</div>
          <el-table :data="stats" size="small" height="240">
            <el-table-column prop="stationName" label="工位" width="80" />
            <el-table-column prop="totalCount" label="检测数" width="80" />
            <el-table-column prop="passCount" label="合格数" width="80" />
            <el-table-column label="通过率">
              <template #default="{ row }">
                <el-progress :percentage="Number(String(row.passRate).replace('%', ''))" :stroke-width="12" />
              </template>
            </el-table-column>
          </el-table>
        </div>
        <div class="vc-card">
          <div class="vc-card-title">不合格项排行</div>
          <el-table :data="failRank" size="small" height="240">
            <el-table-column prop="stationName" label="工位" width="80" />
            <el-table-column prop="itemName" label="不合格项" />
            <el-table-column prop="failCount" label="次数" width="70" />
          </el-table>
        </div>
      </div>
    </section>
  </div>

  <!-- 工位检测项录入 -->
  <el-dialog v-model="stationVisible" :title="'工位录入 · ' + stationForm.stationName" width="520px">
    <el-alert type="info" :closable="false" show-icon style="margin-bottom: 12px;">
      判定阈值：外观缺陷 ≤ 2 项 ｜ CO ≤ 0.300% ｜ HC ≤ 50ppm ｜ 制动率 ≥ 60% ｜ 灯光 ≥ 15000cd ｜ 底盘缺陷 0 项
    </el-alert>
    <el-form label-width="120px">
      <el-form-item label="操作员">
        <el-input v-model="stationForm.operatorName" placeholder="如 周尾气" />
      </el-form-item>

      <el-form-item v-if="stationForm.stationType === 'LOGIN'" label="车牌与证件一致">
        <el-switch v-model="stationForm.loginMatched" active-text="一致" inactive-text="不一致" />
      </el-form-item>

      <el-form-item v-if="stationForm.stationType === 'APPEARANCE'" label="外观缺陷项数">
        <el-input-number v-model="stationForm.appearanceDefects" :min="0" :max="20" />
      </el-form-item>

      <template v-if="stationForm.stationType === 'EMISSION'">
        <el-form-item label="CO 值 (%)">
          <el-input-number v-model="stationForm.coValue" :min="0" :max="10" :step="0.01" :precision="3" />
        </el-form-item>
        <el-form-item label="HC 值 (ppm)">
          <el-input-number v-model="stationForm.hcValue" :min="0" :max="999" :step="1" />
        </el-form-item>
      </template>

      <el-form-item v-if="stationForm.stationType === 'BRAKE'" label="制动率 (%)">
        <el-input-number v-model="stationForm.brakeRate" :min="0" :max="100" :step="0.5" :precision="2" />
      </el-form-item>

      <el-form-item v-if="stationForm.stationType === 'LIGHT'" label="灯光强度 (cd)">
        <el-input-number v-model="stationForm.lightIntensity" :min="0" :max="99999" :step="100" />
      </el-form-item>

      <el-form-item v-if="stationForm.stationType === 'CHASSIS'" label="底盘缺陷项">
        <el-checkbox-group v-model="stationForm.chassisDefects">
          <el-checkbox v-for="d in CHASSIS_OPTIONS" :key="d" :value="d" :label="d" />
        </el-checkbox-group>
      </el-form-item>

      <el-form-item v-if="stationForm.stationType === 'FINAL'" label="复核意见">
        <el-input v-model="stationForm.finalComment" type="textarea" :rows="2" placeholder="总检复核意见" />
      </el-form-item>

      <el-form-item label="自动判定">
        <span :class="previewPass ? 'vc-tag-pass' : 'vc-tag-fail'">
          {{ previewPass ? '合格 PASS' : '不合格 FAIL' }}
        </span>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="stationVisible = false">取消</el-button>
      <el-button type="primary" @click="submitStation">提交并推进到下一工位</el-button>
    </template>
  </el-dialog>

  <!-- 派发检测任务 -->
  <el-dialog v-model="taskVisible" title="派发检测任务" width="480px">
    <el-form label-width="110px">
      <el-form-item label="车辆">
        <el-select v-model="taskForm.vehicleId" filterable style="width: 100%;">
          <el-option
            v-for="v in vehicles"
            :key="v.id"
            :label="v.plateNo + ' · ' + v.ownerName"
            :value="v.id"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="检测线">
        <el-select v-model="taskForm.lineId" style="width: 100%;">
          <el-option v-for="l in lines" :key="l.id" :label="l.lineNo + ' · ' + l.lineName" :value="l.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="引车员">
        <el-select v-model="taskForm.driverId" clearable style="width: 100%;">
          <el-option v-for="d in drivers" :key="d.id" :label="d.driverName + '（' + d.certLevel + '）'" :value="d.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="排班时段">
        <el-select v-model="taskForm.scheduleHour" clearable style="width: 100%;">
          <el-option v-for="h in HOURS" :key="h" :label="h + ':00'" :value="h" />
        </el-select>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="taskVisible = false">取消</el-button>
      <el-button type="primary" @click="submitTask">派发至流水线起点</el-button>
    </template>
  </el-dialog>

  <!-- 车辆建档 -->
  <el-dialog v-model="vehicleVisible" title="车辆建档（送检登记）" width="520px">
    <el-alert type="info" :closable="false" show-icon style="margin-bottom: 12px;">
      身份证号与手机号以明文提交，后端经 @ColumnTransformer 用 AES_ENCRYPT 落库，查询时解密回明文
    </el-alert>
    <el-form label-width="110px">
      <el-form-item label="车牌"><el-input v-model="vehicleForm.plateNo" placeholder="京A·Q8801" /></el-form-item>
      <el-form-item label="车主"><el-input v-model="vehicleForm.ownerName" /></el-form-item>
      <el-form-item label="身份证号"><el-input v-model="vehicleForm.ownerIdNo" /></el-form-item>
      <el-form-item label="手机号"><el-input v-model="vehicleForm.ownerPhone" /></el-form-item>
      <el-form-item label="车辆类型">
        <el-select v-model="vehicleForm.vehicleType" style="width: 100%;">
          <el-option label="小型客车" value="SMALL_BUS" />
          <el-option label="货车" value="TRUCK" />
          <el-option label="危化品车" value="HAZMAT" />
        </el-select>
      </el-form-item>
      <el-form-item label="燃料类型">
        <el-select v-model="vehicleForm.fuelType" style="width: 100%;">
          <el-option label="汽油" value="GASOLINE" />
          <el-option label="柴油" value="DIESEL" />
          <el-option label="液化天然气" value="LNG" />
          <el-option label="纯电" value="ELECTRIC" />
        </el-select>
      </el-form-item>
      <el-form-item label="注册日期">
        <el-date-picker v-model="vehicleForm.registerDate" type="date" value-format="YYYY-MM-DD" style="width: 100%;" />
      </el-form-item>
      <el-form-item label="里程(km)">
        <el-input-number v-model="vehicleForm.mileage" :min="0" :step="1000" />
      </el-form-item>
      <el-form-item label="送检人"><el-input v-model="vehicleForm.submitter" /></el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="vehicleVisible = false">取消</el-button>
      <el-button type="primary" @click="submitVehicle">建档</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, inject, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import {
  fetchTasks,
  fetchTask,
  createTask,
  submitStationResult,
  fetchStationStats,
  fetchFailRank,
  fetchVehicles,
  fetchVehicle,
  createVehicle,
  fetchLines,
  fetchDrivers,
  fetchAllStations
} from '../api'
import router from '../router'

const HOURS = [8, 9, 10, 11, 12, 13, 14, 15, 16, 17]
const CHASSIS_OPTIONS = [
  '制动管路渗漏',
  '转向横拉杆球头松旷',
  '悬挂弹簧断裂',
  '传动轴万向节松旷',
  '轮胎花纹深度不足'
]

const { moveCapsule, activeStation, clearStation } = inject('pipeline')

const tasks = ref([])
const vehicles = ref([])
const drivers = ref([])
const lines = ref([])
const allStations = ref([])
const stats = ref([])
const failRank = ref([])
const current = ref(null)
const vehicle = ref(null)

const faultStationIds = computed(() =>
  new Set(allStations.value.filter((s) => s.status === 'FAULT').map((s) => s.id)))

function goRecovery () {
  router.push('/recovery')
}

const stationVisible = ref(false)
const taskVisible = ref(false)
const vehicleVisible = ref(false)

const stationForm = reactive({
  stationId: null,
  stationType: '',
  stationName: '',
  operatorName: '',
  loginMatched: true,
  appearanceDefects: 0,
  coValue: 0.12,
  hcValue: 30,
  brakeRate: 68,
  lightIntensity: 18000,
  chassisDefects: [],
  finalComment: ''
})

const taskForm = reactive({ vehicleId: null, lineId: null, driverId: null, scheduleHour: null })
const vehicleForm = reactive({
  plateNo: '',
  ownerName: '',
  ownerIdNo: '',
  ownerPhone: '',
  vehicleType: 'SMALL_BUS',
  fuelType: 'GASOLINE',
  registerDate: '2020-01-01',
  mileage: 50000,
  submitter: ''
})

const currentStation = computed(() =>
  current.value ? { id: current.value.currentStationId, stationType: current.value.currentStationType, stationName: current.value.currentStationName } : null
)

const previewPass = computed(() => {
  switch (stationForm.stationType) {
    case 'LOGIN': return stationForm.loginMatched
    case 'APPEARANCE': return stationForm.appearanceDefects <= 2
    case 'EMISSION': return stationForm.coValue <= 0.3 && stationForm.hcValue <= 50
    case 'BRAKE': return stationForm.brakeRate >= 60
    case 'LIGHT': return stationForm.lightIntensity >= 15000
    case 'CHASSIS': return stationForm.chassisDefects.length === 0
    case 'FINAL': return current.value?.records?.every(
      (r) => !['EMISSION', 'BRAKE', 'LIGHT', 'CHASSIS'].includes(r.stationType) || r.result === 'PASS'
    )
    default: return true
  }
})

// 点击底部流水线节点 → 弹出该工位录入表
watch(activeStation, (node) => {
  if (!node) return
  openStation(node)
  clearStation()
})

async function loadAll () {
  const checking = await fetchTasks('CHECKING')
  const retest = await fetchTasks('RETEST')
  tasks.value = [...checking, ...retest]
  stats.value = await fetchStationStats()
  failRank.value = await fetchFailRank()
  vehicles.value = await fetchVehicles()
  drivers.value = await fetchDrivers()
  lines.value = await fetchLines()
  allStations.value = await fetchAllStations()
}

async function selectTask (t) {
  current.value = await fetchTask(t.id)
  vehicle.value = await fetchVehicle(current.value.vehicleId)
  moveCapsule(current.value.plateNo, current.value.currentSeqNo)
}

async function refresh () {
  await loadAll()
  if (current.value) {
    current.value = await fetchTask(current.value.id)
    moveCapsule(current.value.plateNo, current.value.currentSeqNo)
  }
}

function openStation (node) {
  if (!current.value) {
    ElMessage.warning('请先选择一辆在检车辆')
    return
  }
  if (!node || !node.id) return
  stationForm.stationId = node.id
  stationForm.stationType = node.stationType
  stationForm.stationName = node.stationName
  stationForm.operatorName = node.stationName + '工位'
  stationForm.chassisDefects = []
  stationVisible.value = true
}

async function submitStation () {
  try {
    const updated = await submitStationResult(current.value.id, {
      stationId: stationForm.stationId,
      operatorName: stationForm.operatorName,
      loginMatched: stationForm.loginMatched,
      appearanceDefects: stationForm.appearanceDefects,
      coValue: stationForm.coValue,
      hcValue: stationForm.hcValue,
      brakeRate: stationForm.brakeRate,
      lightIntensity: stationForm.lightIntensity,
      chassisDefects: stationForm.chassisDefects,
      finalComment: stationForm.finalComment
    })
    stationVisible.value = false
    ElMessage.success('已录入并推进到下一工位')
    current.value = await fetchTask(updated.id)
    moveCapsule(current.value.plateNo, current.value.currentSeqNo)
    await loadAll()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

function openTaskDialog () {
  taskForm.vehicleId = vehicles.value[0]?.id ?? null
  taskForm.lineId = lines.value[0]?.id ?? null
  taskForm.driverId = null
  taskForm.scheduleHour = 9
  taskVisible.value = true
}

async function submitTask () {
  try {
    await createTask({ ...taskForm })
    taskVisible.value = false
    ElMessage.success('任务已派发至流水线起点')
    await loadAll()
  } catch (e) {
    if (e.status === 409) {
      // 派发格冲突：本次整笔回滚未落库，关闭弹窗并按最新格子刷新
      taskVisible.value = false
      ElMessage.warning(e.message)
      await loadAll()
    } else {
      ElMessage.error(e.message)
    }
  }
}

function openVehicleDialog () {
  Object.assign(vehicleForm, {
    plateNo: '', ownerName: '', ownerIdNo: '', ownerPhone: '',
    vehicleType: 'SMALL_BUS', fuelType: 'GASOLINE', registerDate: '2020-01-01',
    mileage: 50000, submitter: ''
  })
  vehicleVisible.value = true
}

async function submitVehicle () {
  try {
    await createVehicle({ ...vehicleForm })
    vehicleVisible.value = false
    ElMessage.success('车辆建档成功')
    await loadAll()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

onMounted(async () => {
  await loadAll()
  if (tasks.value.length) {
    await selectTask(tasks.value[0])
  }
})
</script>

<style scoped>
.vc-queue-item {
  border: 1px solid var(--vc-border);
  border-radius: 6px;
  padding: 8px 10px;
  margin-bottom: 8px;
  cursor: pointer;
  transition: all 0.18s ease;
}

.vc-queue-item:hover {
  border-color: var(--vc-primary);
  background: #f5f9fd;
}

.vc-queue-item.active {
  border-color: var(--vc-primary);
  background: #eaf2fa;
  box-shadow: inset 3px 0 0 var(--vc-primary);
}

.vc-queue-plate {
  font-size: 16px;
  font-weight: 700;
  color: var(--vc-primary-deep);
}

.vc-queue-meta {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
  color: var(--vc-muted);
  margin: 3px 0;
}

.vc-queue-foot {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
}

.vc-flow-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
}

.vc-flow-plate {
  font-size: 22px;
  font-weight: 800;
  color: var(--vc-primary-deep);
  letter-spacing: 1px;
}

.vc-flow-now {
  text-align: center;
  padding: 8px 14px;
  border: 1px dashed var(--vc-primary);
  border-radius: 8px;
  background: #f4f9fd;
  min-width: 150px;
}

.vc-flow-now-seq {
  font-size: 12px;
  color: var(--vc-muted);
}

.vc-flow-now-name {
  font-size: 18px;
  font-weight: 700;
  color: var(--vc-primary);
  margin: 2px 0 6px;
}
</style>
