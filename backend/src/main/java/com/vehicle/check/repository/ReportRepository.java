package com.vehicle.check.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.vehicle.check.domain.Report;
import com.vehicle.check.dto.LineReportStatDTO;

public interface ReportRepository extends JpaRepository<Report, Long> {

    List<Report> findAllByOrderByIdDesc();

    List<Report> findByTaskIdOrderByIdDesc(Long taskId);

    List<Report> findByConclusionOrderBySignDateDesc(String conclusion);

    long countBySignDate(LocalDate signDate);

    /**
     * 按检测线统计报告数与不合格数
     */
    @Query("select new com.vehicle.check.dto.LineReportStatDTO(r.lineNo, count(r), "
            + "sum(case when r.conclusion = 'FAIL' then 1 else 0 end)) "
            + "from Report r "
            + "group by r.lineNo")
    List<LineReportStatDTO> statReportByLine();

    /**
     * 当日不合格报告（复检工作台用）
     */
    @Query("select r from Report r "
            + "where r.conclusion = 'FAIL' and r.signDate = :date "
            + "order by r.id desc")
    List<Report> findFailReportsOfDate(@Param("date") LocalDate date);
}
