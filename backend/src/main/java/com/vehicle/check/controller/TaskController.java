package com.vehicle.check.controller;

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
import com.vehicle.check.dto.FailItemDTO;
import com.vehicle.check.dto.OverviewVO;
import com.vehicle.check.dto.StationResultRequest;
import com.vehicle.check.dto.StationStatDTO;
import com.vehicle.check.dto.TaskCreateRequest;
import com.vehicle.check.dto.TaskVO;
import com.vehicle.check.repository.DeviceRepository;
import com.vehicle.check.repository.LineRepository;
import com.vehicle.check.repository.ReportRepository;
import com.vehicle.check.repository.StationRepository;
import com.vehicle.check.repository.VehicleRepository;
import com.vehicle.check.service.ResourceService;
import com.vehicle.check.service.TaskFlowService;

/**
 * 检测任务与工位流转
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskFlowService taskFlowService;
    private final ResourceService resourceService;
    private final LineRepository lineRepository;
    private final StationRepository stationRepository;
    private final VehicleRepository vehicleRepository;
    private final ReportRepository reportRepository;
    private final DeviceRepository deviceRepository;

    public TaskController(TaskFlowService taskFlowService, ResourceService resourceService,
                          LineRepository lineRepository, StationRepository stationRepository,
                          VehicleRepository vehicleRepository, ReportRepository reportRepository,
                          DeviceRepository deviceRepository) {
        this.taskFlowService = taskFlowService;
        this.resourceService = resourceService;
        this.lineRepository = lineRepository;
        this.stationRepository = stationRepository;
        this.vehicleRepository = vehicleRepository;
        this.reportRepository = reportRepository;
        this.deviceRepository = deviceRepository;
    }

    /** 任务列表，可按 status 过滤（CHECKING/PASS/FAIL/RETEST） */
    @GetMapping
    public ApiResponse<List<TaskVO>> list(@RequestParam(required = false) String status) {
        return ApiResponse.success(taskFlowService.listTasks(status));
    }

    @GetMapping("/{id}")
    public ApiResponse<TaskVO> detail(@PathVariable Long id) {
        return ApiResponse.success(taskFlowService.getTask(id));
    }

    /** 建立检测任务（送检派单） */
    @PostMapping
    public ApiResponse<TaskVO> create(@RequestBody TaskCreateRequest request) {
        return ApiResponse.success("任务已派发至流水线起点", taskFlowService.createTask(request));
    }

    /** 工位检测项录入：数值 + 自动判定 + 推进到下一工位 */
    @PostMapping("/{id}/station-result")
    public ApiResponse<TaskVO> submitResult(@PathVariable Long id, @RequestBody StationResultRequest request) {
        return ApiResponse.success(taskFlowService.submitStationResult(id, request));
    }

    /** 按工位统计通过率 */
    @GetMapping("/station-stats")
    public ApiResponse<List<StationStatDTO>> stationStats() {
        return ApiResponse.success(taskFlowService.stationStats());
    }

    /** 不合格项排行 */
    @GetMapping("/fail-rank")
    public ApiResponse<List<FailItemDTO>> failRank() {
        return ApiResponse.success(taskFlowService.failRank());
    }

    /** 概览统计 */
    @GetMapping("/overview")
    public ApiResponse<OverviewVO> overview() {
        return ApiResponse.success(taskFlowService.overview(
                lineRepository.count(),
                stationRepository.count(),
                vehicleRepository.count(),
                reportRepository.count(),
                reportRepository.count() - reportRepository.findByConclusionOrderBySignDateDesc("PASS").size(),
                resourceService.calStat().getOverdueCount()));
    }

    /** 派发并发冲突：本次未生效（整笔已回滚），前端按提示刷新格子 */
    @ExceptionHandler(ScheduleConflictException.class)
    public ResponseEntity<ApiResponse<Void>> handleScheduleConflict(ScheduleConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.fail(e.getMessage()));
    }
}
