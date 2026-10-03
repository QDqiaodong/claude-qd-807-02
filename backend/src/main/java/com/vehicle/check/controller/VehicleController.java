package com.vehicle.check.controller;

import java.time.LocalDate;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.vehicle.check.common.ApiResponse;
import com.vehicle.check.domain.Vehicle;
import com.vehicle.check.dto.VehicleRequest;
import com.vehicle.check.service.VehicleService;

/**
 * 车辆与送检
 */
@RestController
@RequestMapping("/api/vehicles")
public class VehicleController {

    private final VehicleService vehicleService;

    public VehicleController(VehicleService vehicleService) {
        this.vehicleService = vehicleService;
    }

    /**
     * 车辆列表。ownerIdNo / ownerPhone 库里是 AES 密文，
     * 由 @ColumnTransformer 自动解密，接口直接返回明文。
     */
    @GetMapping
    public ApiResponse<List<Vehicle>> list() {
        return ApiResponse.success(vehicleService.listVehicles());
    }

    @GetMapping("/{id}")
    public ApiResponse<Vehicle> detail(@PathVariable Long id) {
        return ApiResponse.success(vehicleService.getVehicle(id));
    }

    /** 车辆建档（送检登记） */
    @PostMapping
    public ApiResponse<Vehicle> create(@RequestBody VehicleRequest request) {
        return ApiResponse.success("建档成功", vehicleService.createVehicle(request));
    }

    /** 当日尚未派单的车辆（派单下拉用） */
    @GetMapping("/assignable")
    public ApiResponse<List<Vehicle>> assignable(@RequestParam(required = false) String date) {
        LocalDate day = date == null || date.isBlank() ? LocalDate.now() : LocalDate.parse(date);
        return ApiResponse.success(vehicleService.listAssignable(day));
    }
}
