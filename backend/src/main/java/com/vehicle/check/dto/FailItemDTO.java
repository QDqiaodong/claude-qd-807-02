package com.vehicle.check.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 不合格项排行
 */
@Data
@NoArgsConstructor
public class FailItemDTO {

    private String stationType;
    private String stationName;
    private String itemName;
    private Long failCount;

    public FailItemDTO(String stationType, String itemName, Long failCount) {
        this.stationType = stationType;
        this.itemName = itemName;
        this.failCount = failCount == null ? 0L : failCount;
    }
}
