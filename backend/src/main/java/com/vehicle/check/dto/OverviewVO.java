package com.vehicle.check.dto;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/**
 * 首页概览统计
 */
@Data
public class OverviewVO {

    private Long lineCount;
    private Long stationCount;
    private Long vehicleCount;
    private Long checkingCount;
    private Long reportCount;
    private Long failReportCount;
    private Long overdueDeviceCount;
    private Long redisCheckedCount;
    private List<StationStatDTO> stationStats = new ArrayList<>();
    private List<FailItemDTO> failRank = new ArrayList<>();
}
