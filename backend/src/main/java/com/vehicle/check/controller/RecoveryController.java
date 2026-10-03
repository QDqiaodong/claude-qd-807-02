package com.vehicle.check.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.vehicle.check.common.ApiResponse;
import com.vehicle.check.dto.AffectedTaskVO;
import com.vehicle.check.dto.RecoveryBoardVO;
import com.vehicle.check.dto.TransferConfirmRequest;
import com.vehicle.check.dto.TransferVO;
import com.vehicle.check.service.RecoveryService;

/**
 * 在检车辆跨线恢复处置台：
 * 受影响车辆 + 候选检测线推荐/排除原因 + 确认转线（并发只成功一次）+ 无目标时保持待处置。
 */
@RestController
@RequestMapping("/api/recovery")
public class RecoveryController {

    private final RecoveryService recoveryService;

    public RecoveryController(RecoveryService recoveryService) {
        this.recoveryService = recoveryService;
    }

    /** 处置台：仍在检测中、卡在故障工位的车辆与候选线评估、最近处置记录 */
    @GetMapping("/board")
    public ApiResponse<RecoveryBoardVO> board() {
        return ApiResponse.success(recoveryService.board());
    }

    /** 单辆车的处置详情（候选线实时评估） */
    @GetMapping("/tasks/{taskId}")
    public ApiResponse<AffectedTaskVO> affected(@PathVariable Long taskId) {
        return ApiResponse.success(recoveryService.affectedTask(taskId));
    }

    /** 确认转线：同车两人同时确认 / 目标工位瞬间被占用时只成功一次，失败者看到最新去向 */
    @PostMapping("/tasks/{taskId}/transfer")
    public ApiResponse<TransferVO> transfer(@PathVariable Long taskId,
                                            @RequestBody TransferConfirmRequest request) {
        return ApiResponse.success("跨线恢复成功：已完成工位结果原样保留，自目标线第 "
                + "对应工位继续检测", recoveryService.confirmTransfer(taskId, request));
    }

    /** 无可用目标：保持待处置并保留故障前进度；故障解除后可回原线继续 */
    @PostMapping("/tasks/{taskId}/keep-pending")
    public ApiResponse<TransferVO> keepPending(@PathVariable Long taskId,
                                               @RequestBody TransferConfirmRequest request) {
        return ApiResponse.success("已登记：车辆保持待处置，故障前进度完整保留",
                recoveryService.keepPending(taskId, request));
    }
}
