package com.vehicle.check.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.vehicle.check.common.ApiResponse;
import com.vehicle.check.common.BizException;
import com.vehicle.check.domain.Line;
import com.vehicle.check.domain.Station;
import com.vehicle.check.dto.StationVO;
import com.vehicle.check.service.LineService;

/**
 * 检测线与工位
 */
@RestController
@RequestMapping("/api/lines")
public class LineController {

    private final LineService lineService;

    public LineController(LineService lineService) {
        this.lineService = lineService;
    }

    /** 检测线列表 */
    @GetMapping
    public ApiResponse<List<Line>> listLines() {
        return ApiResponse.success(lineService.listLines());
    }

    /** 某条检测线的工位（含排队车辆数徽章） */
    @GetMapping("/{id}/stations")
    public ApiResponse<List<StationVO>> stations(@PathVariable Long id) {
        return ApiResponse.success(lineService.stationsOfLine(id));
    }

    /** 全部工位 */
    @GetMapping("/stations")
    public ApiResponse<List<StationVO>> allStations() {
        return ApiResponse.success(lineService.listAllStations());
    }

    /** 底部流水线导航：默认线 7 个工位 + 排队数 */
    @GetMapping("/stations/pipeline")
    public ApiResponse<List<StationVO>> pipeline() {
        return ApiResponse.success(lineService.pipeline());
    }

    /** 修改工位状态：空闲 / 占用 / 故障 */
    @PostMapping("/stations/{id}/status")
    public ApiResponse<Station> updateStatus(@PathVariable Long id, @RequestParam String status) {
        if (status == null || status.isBlank()) {
            throw new BizException("工位状态不能为空");
        }
        return ApiResponse.success(lineService.updateStationStatus(id, status));
    }

    /** 工位状态分布 */
    @GetMapping("/stations/status-count")
    public ApiResponse<Map<String, Long>> statusCount() {
        return ApiResponse.success(lineService.stationStatusCount());
    }
}
