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
import com.vehicle.check.dto.IssueReportRequest;
import com.vehicle.check.dto.LineReportStatDTO;
import com.vehicle.check.dto.ReportVO;
import com.vehicle.check.dto.RetestRequest;
import com.vehicle.check.dto.TaskVO;
import com.vehicle.check.service.ReportService;

/**
 * 检测报告：签发、徽章墙、安排复检
 */
@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    /** 报告列表（含徽章墙与不合格项） */
    @GetMapping
    public ApiResponse<List<ReportVO>> list() {
        return ApiResponse.success(reportService.listReports());
    }

    @GetMapping("/{id}")
    public ApiResponse<ReportVO> detail(@PathVariable Long id) {
        return ApiResponse.success(reportService.getReport(id));
    }

    @GetMapping("/no/{reportNo}")
    public ApiResponse<ReportVO> byNo(@PathVariable String reportNo) {
        return ApiResponse.success(reportService.getReportByNo(reportNo));
    }

    /** 签发报告：校验设备标定有效期与关键项结论 */
    @PostMapping("/tasks/{taskId}/issue")
    public ApiResponse<ReportVO> issue(@PathVariable Long taskId, @RequestBody IssueReportRequest request) {
        return ApiResponse.success("报告已签发", reportService.issueReport(taskId, request));
    }

    /** 安排复检：把不合格车辆送回流水线起点 */
    @PostMapping("/{id}/retest")
    public ApiResponse<TaskVO> retest(@PathVariable Long id, @RequestBody RetestRequest request) {
        return ApiResponse.success("已安排复检，车辆送回流水线起点", reportService.retest(id, request));
    }

    /** 按检测线统计报告数与不合格数 */
    @GetMapping("/line-stats")
    public ApiResponse<List<LineReportStatDTO>> lineStats() {
        return ApiResponse.success(reportService.lineStats());
    }

    /** 当日不合格报告（复检工作台） */
    @GetMapping("/fail-today")
    public ApiResponse<List<ReportVO>> failToday(@RequestParam(required = false) String date) {
        LocalDate day = date == null || date.isBlank() ? LocalDate.now() : LocalDate.parse(date);
        return ApiResponse.success(reportService.failReportsOfDate(day));
    }

    /** 复检派发并发冲突：本次未生效（整笔已回滚），前端按提示刷新格子 */
    @ExceptionHandler(ScheduleConflictException.class)
    public ResponseEntity<ApiResponse<Void>> handleScheduleConflict(ScheduleConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.fail(e.getMessage()));
    }
}
