package com.vehicle.check.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 按工位统计通过率
 */
@Data
@NoArgsConstructor
public class StationStatDTO {

    private String stationType;
    private String stationName;
    private Long totalCount;
    private Long passCount;
    private String passRate;

    public StationStatDTO(String stationType, Long totalCount, Long passCount) {
        this.stationType = stationType;
        this.totalCount = totalCount == null ? 0L : totalCount;
        this.passCount = passCount == null ? 0L : passCount;
        this.passRate = this.totalCount == 0
                ? "0.0%"
                : String.format("%.1f%%", this.passCount * 100.0 / this.totalCount);
    }
}
