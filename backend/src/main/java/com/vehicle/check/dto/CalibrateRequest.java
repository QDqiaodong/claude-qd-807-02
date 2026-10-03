package com.vehicle.check.dto;

import java.time.LocalDate;

import lombok.Data;

/**
 * 登记标定结果请求：标定机构、结论、下次标定日期
 */
@Data
public class CalibrateRequest {

    private String calOrg;
    private String calResult;
    private LocalDate lastCalDate;
    private LocalDate nextCalDate;
}
