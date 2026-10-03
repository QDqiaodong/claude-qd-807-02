import { createRouter, createWebHistory } from 'vue-router'
import FlowView from '../views/FlowView.vue'
import ReportView from '../views/ReportView.vue'
import ScheduleView from '../views/ScheduleView.vue'
import DeviceView from '../views/DeviceView.vue'
import RecoveryView from '../views/RecoveryView.vue'

const routes = [
  { path: '/', redirect: '/flow' },
  { path: '/flow', name: 'flow', component: FlowView, meta: { title: '工位流水线' } },
  { path: '/recovery', name: 'recovery', component: RecoveryView, meta: { title: '跨线恢复' } },
  { path: '/report', name: 'report', component: ReportView, meta: { title: '检测报告' } },
  { path: '/schedule', name: 'schedule', component: ScheduleView, meta: { title: '引车员排班' } },
  { path: '/device', name: 'device', component: DeviceView, meta: { title: '设备标定' } }
]

export default createRouter({
  history: createWebHistory(),
  routes
})
