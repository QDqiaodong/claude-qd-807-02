package com.vehicle.check.dto;

import java.time.LocalDate;

import lombok.Data;

/**
 * 签发报告请求：签发人 + 签发日期（结论由关键项自动判定，不允许手动放宽）
 */
@Data
public class IssueReportRequest {

    private String signer;
    private LocalDate signDate;
    private String retestOf;
}
