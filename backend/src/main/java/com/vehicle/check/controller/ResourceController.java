package com.vehicle.check.controller;

import java.time.LocalDate;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.vehicle.check.common.ApiResponse;
import com.vehicle.check.common.ScheduleConflictException;
import com.vehicle.check.domain.Driver;
import com.vehicle.check.dto.CalibrateRequest;
import com.vehicle.check.dto.CalibrateStatDTO;
import com.vehicle.check.dto.DeviceVO;
import com.vehicle.check.dto.DispatchEligibilityVO;
import com.vehicle.check.dto.DispatchRequest;
import com.vehicle.check.dto.DispatchResultVO;
import com.vehicle.check.dto.DriverStatDTO;
import com.vehicle.check.dto.ReassignRequest;
import com.vehicle.check.dto.ScheduleRowVO;
import com.vehicle.check.service.DispatchService;
import com.vehicle.check.service.ResourceService;

import jakarta.validation.Valid;

/**
 * 设备标定与引车员排班。
 * 排班工作流：格子表恢复服务端状态 → 资格预检看阻断原因 → 派发（任务/小时格/队列起点一次绑定）
 * → 未开始任务可改派（并发下返回当前归属）。
 */
@RestController
@RequestMapping("/api")
public class ResourceController {

    private final ResourceService resourceService;
    private final DispatchService dispatchService;

    public ResourceController(ResourceService resourceService, DispatchService dispatchService) {
        this.resourceService = resourceService;
        this.dispatchService = dispatchService;
    }

    // ---------------- 设备 ----------------

    /** 设备台账（含下次标定剩余天数与预警级别） */
    @GetMapping("/devices")
    public ApiResponse<List<DeviceVO>> devices() {
        return ApiResponse.success(resourceService.listDevices());
    }

    @GetMapping("/devices/{id}")
    public ApiResponse<DeviceVO> device(@PathVariable Long id) {
        return ApiResponse.success(resourceService.getDevice(id));
    }

    /** 登记标定结果，自动重置倒计时 */
    @PostMapping("/devices/{id}/calibrate")
    public ApiResponse<DeviceVO> calibrate(@PathVariable Long id, @RequestBody CalibrateRequest request) {
        return ApiResponse.success("标定结果已登记，倒计时已重置", resourceService.calibrate(id, request));
    }

    /** 标定状态统计 */
    @GetMapping("/devices/cal-stat")
    public ApiResponse<CalibrateStatDTO> calStat() {
        return ApiResponse.success(resourceService.calStat());
    }

    // ---------------- 引车员排班 ----------------

    @GetMapping("/drivers")
    public ApiResponse<List<Driver>> drivers() {
        return ApiResponse.success(resourceService.listDrivers());
    }

    /** 排班格子表：重新打开即恢复服务端格子状态（待派/在检/合格/不合格/冲突原因） */
    @GetMapping("/drivers/schedule")
    public ApiResponse<List<ScheduleRowVO>> schedule(@RequestParam(required = false) String date) {
        LocalDate day = date == null || date.isBlank() ? LocalDate.now() : LocalDate.parse(date);
        return ApiResponse.success(resourceService.schedule(day));
    }

    /** 派发资格预检：不写库，逐条返回阻断原因，供调度员点格时查看 */
    @PostMapping("/dispatch/check")
    public ApiResponse<DispatchEligibilityVO> checkDispatch(@Valid @RequestBody DispatchRequest request) {
        DispatchEligibilityVO vo = dispatchService.checkDispatch(request);
        return ApiResponse.success(vo.getEligible() ? "资格校验通过" : "存在阻断原因", vo);
    }

    /** 改派资格预检：不写库，返回目标引车员/小时格的阻断原因 */
    @GetMapping("/tasks/{taskId}/reassign/check")
    public ApiResponse<DispatchEligibilityVO> checkReassign(@PathVariable Long taskId,
                                                            @RequestParam Long driverId,
                                                            @RequestParam(required = false) Integer hour) {
        DispatchEligibilityVO vo = dispatchService.checkReassign(taskId, driverId, hour);
        return ApiResponse.success(vo.getEligible() ? "资格校验通过" : "存在阻断原因", vo);
    }

    /** 派发：任务、小时格、车辆队列起点同一事务绑定一次完成 */
    @PostMapping("/dispatch")
    public ApiResponse<DispatchResultVO> dispatch(@Valid @RequestBody DispatchRequest request) {
        return ApiResponse.success("派发成功", dispatchService.dispatch(request));
    }

    /**
     * 改派：仅未开始检测的任务可改。并发改派 / 重复点击 / 已开始结束任务，
     * 走 409 + 当前归属（同响应体结构），前端按返回格子刷新，绝不出现一任务占两格。
     */
    @PostMapping("/tasks/{taskId}/reassign")
    public ApiResponse<DispatchResultVO> reassign(@PathVariable Long taskId,
                                                  @Valid @RequestBody ReassignRequest request) {
        return ApiResponse.success("改派成功", dispatchService.reassign(taskId, request));
    }

    /**
     * 并发冲突统一成 409：message 说明本次为何没生效，data 带任务当前归属（格子 + 详情 + 台次）。
     */
    @ExceptionHandler(ScheduleConflictException.class)
    public ResponseEntity<ApiResponse<DispatchResultVO>> handleScheduleConflict(ScheduleConflictException e) {
        DispatchResultVO current = e.getTaskId() == null ? null
                : dispatchService.currentOwnership(e.getTaskId());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiResponse<>(false, e.getMessage(), current));
    }

    /** 按引车员统计当日台次（未结束台次 = 有效小时格数） */
    @GetMapping("/drivers/stats")
    public ApiResponse<List<DriverStatDTO>> driverStats(@RequestParam(required = false) String date) {
        LocalDate day = date == null || date.isBlank() ? LocalDate.now() : LocalDate.parse(date);
        return ApiResponse.success(dispatchService.driverStats(day));
    }
}
