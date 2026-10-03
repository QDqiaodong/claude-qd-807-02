package com.vehicle.check.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 按检测线统计报告数与不合格数
 */
@Data
@NoArgsConstructor
public class LineReportStatDTO {

    private String lineNo;
    private Long reportCount;
    private Long failCount;

    public LineReportStatDTO(String lineNo, Long reportCount, Long failCount) {
        this.lineNo = lineNo;
        this.reportCount = reportCount == null ? 0L : reportCount;
        this.failCount = failCount == null ? 0L : failCount;
    }
}
