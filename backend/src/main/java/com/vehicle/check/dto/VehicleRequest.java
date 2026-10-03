package com.vehicle.check.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

import lombok.Data;

/**
 * 车辆建档请求（身份证号/手机号提交明文，落库为 AES 密文）
 */
@Data
public class VehicleRequest {

    private String plateNo;
    private String ownerName;
    private String ownerIdNo;
    private String ownerPhone;
    private String vehicleType;
    private LocalDate registerDate;
    private Integer mileage;
    private String fuelType;
    private String submitter;
    private LocalDateTime appointTime;
}
