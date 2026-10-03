package com.vehicle.check.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.vehicle.check.domain.Device;
import com.vehicle.check.dto.CalibrateStatDTO;

public interface DeviceRepository extends JpaRepository<Device, Long> {

    List<Device> findAllByOrderByNextCalDateAsc();

    List<Device> findByDeviceTypeOrderByIdAsc(String deviceType);

    /**
     * 超期与临期设备台数统计
     */
    @Query("select new com.vehicle.check.dto.CalibrateStatDTO("
            + "sum(case when d.nextCalDate < :today then 1 else 0 end), "
            + "sum(case when d.nextCalDate >= :today and d.nextCalDate <= :due then 1 else 0 end)) "
            + "from Device d")
    CalibrateStatDTO statCalibration(@Param("today") LocalDate today, @Param("due") LocalDate due);

    /**
     * 某检测线关联工位上的全部设备（用于签发报告前校验标定有效期）
     */
    @Query("select d from Device d "
            + "where d.id in (select s.deviceId from Station s where s.lineId = :lineId and s.deviceId is not null)")
    List<Device> findDevicesOfLine(@Param("lineId") Long lineId);
}
