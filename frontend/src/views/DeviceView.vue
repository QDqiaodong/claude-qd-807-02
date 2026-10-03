<template>
  <div style="display: grid; gap: 14px;">
    <div class="vc-card">
      <div class="vc-card-title">
        设备标定倒计时
        <span class="vc-muted" style="font-weight: 400; font-size: 12px;">
          剩余 &lt; 7 天转橙 ｜ 已过期转红并闪烁 ｜ 点击卡片登记标定结果
        </span>
        <div style="margin-left: auto; display: flex; gap: 10px;">
          <el-tag type="danger">超期 {{ calStat.overdueCount ?? 0 }} 台</el-tag>
          <el-tag type="warning">临期 {{ calStat.dueCount ?? 0 }} 台</el-tag>
          <el-button size="small" @click="load">刷新</el-button>
        </div>
      </div>

      <div class="vc-devices">
        <div
          v-for="d in devices"
          :key="d.id"
          class="vc-dev"
          :class="d.level"
          @click="openCalibrate(d)"
        >
          <div class="vc-dev-bubble">
            <b>{{ d.remainDays }}</b>
            <span>{{ d.remainDays < 0 ? '已超期' : '天后标定' }}</span>
          </div>
          <div class="vc-dev-name">{{ d.deviceName }}</div>
          <div class="vc-dev-model vc-mono">{{ d.model }}</div>
          <div class="vc-dev-meta">上次标定 {{ d.lastCalDate }}</div>
          <div class="vc-dev-meta">下次标定 {{ d.nextCalDate }}</div>
          <div class="vc-dev-meta">机构 {{ d.calOrg || '-' }} ｜ {{ d.deviceTypeName }}</div>
          <el-tag size="small" :type="d.level === 'OVERDUE' ? 'danger' : (d.level === 'DUE' ? 'warning' : 'success')">
            {{ d.statusName }}
          </el-tag>
        </div>
      </div>
    </div>

    <div class="vc-card">
      <div class="vc-card-title">设备台账明细</div>
      <el-table :data="devices" size="small">
        <el-table-column prop="deviceName" label="设备名称" width="150" />
        <el-table-column prop="model" label="型号" width="130" />
        <el-table-column prop="deviceTypeName" label="用途" width="90" />
        <el-table-column prop="lastCalDate" label="上次标定" width="120" />
        <el-table-column prop="nextCalDate" label="下次标定" width="120" />
        <el-table-column label="剩余天数" width="100">
          <template #default="{ row }">
            <span :class="row.remainDays < 0 ? 'vc-tag-fail' : (row.remainDays <= 7 ? 'vc-tag-warn' : 'vc-tag-pass')">
              {{ row.remainDays }} 天
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="calResult" label="上次结论" width="100" />
        <el-table-column label="操作">
          <template #default="{ row }">
            <el-button size="small" type="primary" plain @click="openCalibrate(row)">登记标定</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </div>

  <el-dialog v-model="visible" :title="'登记标定结果 · ' + form.deviceName" width="460px">
    <el-form label-width="110px">
      <el-form-item label="标定机构">
        <el-input v-model="form.calOrg" placeholder="如 省计量院" />
      </el-form-item>
      <el-form-item label="标定结论">
        <el-radio-group v-model="form.calResult">
          <el-radio value="PASS">合格</el-radio>
          <el-radio value="FAIL">不合格</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="本次标定日">
        <el-date-picker v-model="form.lastCalDate" type="date" value-format="YYYY-MM-DD" style="width: 100%;" />
      </el-form-item>
      <el-form-item label="下次标定日">
        <el-date-picker v-model="form.nextCalDate" type="date" value-format="YYYY-MM-DD" style="width: 100%;" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" @click="submit">登记并重置倒计时</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { fetchDevices, fetchCalStat, calibrateDevice } from '../api'

const devices = ref([])
const calStat = ref({})
const visible = ref(false)
const form = reactive({
  id: null,
  deviceName: '',
  calOrg: '',
  calResult: 'PASS',
  lastCalDate: null,
  nextCalDate: null
})

async function load () {
  devices.value = await fetchDevices()
  calStat.value = await fetchCalStat()
}

function openCalibrate (d) {
  form.id = d.id
  form.deviceName = d.deviceName
  form.calOrg = d.calOrg || '省计量院'
  form.calResult = 'PASS'
  form.lastCalDate = new Date().toISOString().slice(0, 10)
  const next = new Date()
  next.setMonth(next.getMonth() + 6)
  form.nextCalDate = next.toISOString().slice(0, 10)
  visible.value = true
}

async function submit () {
  try {
    const updated = await calibrateDevice(form.id, {
      calOrg: form.calOrg,
      calResult: form.calResult,
      lastCalDate: form.lastCalDate,
      nextCalDate: form.nextCalDate
    })
    visible.value = false
    ElMessage.success('已登记：' + updated.deviceName + ' 剩余 ' + updated.remainDays + ' 天')
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

onMounted(load)
</script>

<style scoped>
.vc-devices {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(210px, 1fr));
  gap: 12px;
}

.vc-dev {
  position: relative;
  border: 1px solid var(--vc-border);
  border-radius: 10px;
  padding: 12px 14px 14px;
  background: #fff;
  cursor: pointer;
  transition: all 0.2s ease;
  overflow: hidden;
}

.vc-dev:hover {
  transform: translateY(-3px);
  box-shadow: 0 12px 22px rgba(20, 45, 75, 0.14);
}

.vc-dev::before {
  content: '';
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 4px;
}

.vc-dev.NORMAL::before {
  background: var(--vc-pass);
}

.vc-dev.DUE::before {
  background: var(--vc-warn);
}

.vc-dev.OVERDUE::before {
  background: var(--vc-fail);
}

.vc-dev-bubble {
  width: 86px;
  height: 86px;
  border-radius: 50%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  margin: 4px 0 10px auto;
}

.vc-dev.NORMAL .vc-dev-bubble {
  background: radial-gradient(circle, #eafaf1 0%, #d6f2e3 100%);
  border: 3px solid var(--vc-pass);
  color: var(--vc-pass);
}

.vc-dev.DUE .vc-dev-bubble {
  background: radial-gradient(circle, #fff5e6 0%, #fbe3bf 100%);
  border: 3px solid var(--vc-warn);
  color: var(--vc-warn);
}

.vc-dev.OVERDUE .vc-dev-bubble {
  background: radial-gradient(circle, #fdeceb 0%, #f9d5d2 100%);
  border: 3px solid var(--vc-fail);
  color: var(--vc-fail);
  animation: vc-blink 1.1s infinite;
}

.vc-dev-bubble b {
  font-size: 32px;
  line-height: 1;
}

.vc-dev-bubble span {
  font-size: 11px;
  margin-top: 3px;
}

.vc-dev-name {
  font-size: 15px;
  font-weight: 700;
  color: #17324a;
}

.vc-dev-model {
  font-size: 12px;
  color: var(--vc-primary);
  margin-bottom: 4px;
}

.vc-dev-meta {
  font-size: 12px;
  color: var(--vc-muted);
  padding: 1px 0;
}

.vc-tag-warn {
  color: var(--vc-warn);
  font-weight: 600;
}

@keyframes vc-blink {
  0%, 100% { opacity: 1; box-shadow: 0 0 0 0 rgba(216, 52, 44, 0.45); }
  50% { opacity: 0.72; box-shadow: 0 0 0 12px rgba(216, 52, 44, 0); }
}
</style>
