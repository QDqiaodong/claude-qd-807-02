package com.vehicle.check.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.vehicle.check.common.BizException;
import com.vehicle.check.domain.Task;
import com.vehicle.check.domain.Vehicle;
import com.vehicle.check.dto.VehicleRequest;
import com.vehicle.check.repository.TaskRepository;
import com.vehicle.check.repository.VehicleRepository;

/**
 * 车辆与送检。
 * 注意：ownerIdNo / ownerPhone 由 @ColumnTransformer 自动加解密，本服务全程按明文处理。
 */
@Service
public class VehicleService {

    private final VehicleRepository vehicleRepository;
    private final TaskRepository taskRepository;

    public VehicleService(VehicleRepository vehicleRepository, TaskRepository taskRepository) {
        this.vehicleRepository = vehicleRepository;
        this.taskRepository = taskRepository;
    }

    public List<Vehicle> listVehicles() {
        return vehicleRepository.findAllByOrderByIdAsc();
    }

    public Vehicle getVehicle(Long id) {
        return vehicleRepository.findById(id)
                .orElseThrow(() -> new BizException("车辆不存在：id=" + id));
    }

    /**
     * 车辆建档（送检登记）
     */
    public Vehicle createVehicle(VehicleRequest request) {
        if (request.getPlateNo() == null || request.getPlateNo().isBlank()) {
            throw new BizException("车牌不能为空");
        }
        String plateNo = request.getPlateNo().trim();
        vehicleRepository.findByPlateNo(plateNo).ifPresent(exist -> {
            throw new BizException("车牌「" + plateNo + "」已建档（车辆编号 " + exist.getId() + "，车主 "
                    + exist.getOwnerName() + "），同一车辆不可重复建档");
        });
        if (request.getOwnerName() == null || request.getOwnerName().isBlank()) {
            throw new BizException("车主姓名不能为空");
        }
        if (!Set.of("SMALL_BUS", "TRUCK", "HAZMAT").contains(request.getVehicleType())) {
            throw new BizException("车辆类型只能是 小型客车(SMALL_BUS) / 货车(TRUCK) / 危化品车(HAZMAT)");
        }
        if (!Set.of("GASOLINE", "DIESEL", "LNG", "ELECTRIC").contains(request.getFuelType())) {
            throw new BizException("燃料类型只能是 汽油(GASOLINE) / 柴油(DIESEL) / 液化天然气(LNG) / 纯电(ELECTRIC)");
        }

        Vehicle vehicle = new Vehicle();
        vehicle.setPlateNo(plateNo);
        vehicle.setOwnerName(request.getOwnerName().trim());
        vehicle.setOwnerIdNo(request.getOwnerIdNo());
        vehicle.setOwnerPhone(request.getOwnerPhone());
        vehicle.setVehicleType(request.getVehicleType());
        vehicle.setRegisterDate(request.getRegisterDate() == null ? LocalDate.now() : request.getRegisterDate());
        vehicle.setMileage(request.getMileage() == null ? 0 : request.getMileage());
        vehicle.setFuelType(request.getFuelType());
        vehicle.setSubmitter(request.getSubmitter() == null ? request.getOwnerName() : request.getSubmitter());
        vehicle.setAppointTime(request.getAppointTime());
        vehicle.setCreateTime(java.time.LocalDateTime.now());
        return vehicleRepository.save(vehicle);
    }

    /**
     * 可派单车辆：当日还没有检测任务的车辆
     */
    public List<Vehicle> listAssignable(LocalDate date) {
        Set<Long> busy = taskRepository.findAll().stream()
                .filter(task -> date.equals(task.getCheckDate()))
                .map(Task::getVehicleId)
                .collect(Collectors.toSet());
        List<Vehicle> result = new ArrayList<>();
        for (Vehicle vehicle : vehicleRepository.findAllByOrderByIdAsc()) {
            if (!busy.contains(vehicle.getId())) {
                result.add(vehicle);
            }
        }
        return result;
    }
}
