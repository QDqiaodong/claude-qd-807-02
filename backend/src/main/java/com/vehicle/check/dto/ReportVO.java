package com.vehicle.check.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/**
 * 报告视图：A4 报告卡信息 + 徽章墙
 */
@Data
public class ReportVO {

    private Long id;
    private String reportNo;
    private Long taskId;
    private String taskNo;
    private String plateNo;
    private String ownerName;
    private String vehicleTypeName;
    private String lineNo;
    private String conclusion;
    private String conclusionName;
    private String snapshot;
    private List<String> failItems = new ArrayList<>();
    private List<BadgeVO> badges = new ArrayList<>();
    private String signer;
    private LocalDate signDate;
    private String retestOf;
    private LocalDateTime createTime;
}
