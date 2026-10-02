package com.seatbooking.api.repository;

import com.seatbooking.common.entity.SeatEntity;
import com.seatbooking.common.entity.SeatId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface SeatRepository extends JpaRepository<SeatEntity, SeatId> {

    List<SeatEntity> findByShowId(UUID showId);

    @Query("SELECT s FROM SeatEntity s WHERE s.showId = :showId AND s.seatId IN :seatIds")
    List<SeatEntity> findByShowIdAndSeatIdIn(@Param("showId") UUID showId,
                                              @Param("seatIds") List<String> seatIds);

    @Query("SELECT s.status, COUNT(s) FROM SeatEntity s WHERE s.showId = :showId GROUP BY s.status")
    List<Object[]> countByShowIdGroupByStatus(@Param("showId") UUID showId);
}
