package com.seatbooking.consumer.repository;

import com.seatbooking.common.entity.SeatEntity;
import com.seatbooking.common.entity.SeatId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface ConsumerSeatRepository extends JpaRepository<SeatEntity, SeatId> {

    /**
     * Atomic seat status update — guarded by current status.
     * This is the Postgres-level safety net against double-sell.
     * The WHERE clause ensures we only update if the seat is in a valid state.
     */
    @Modifying
    @Query("UPDATE SeatEntity s SET s.status = :newStatus, s.reservationId = :reservationId " +
            "WHERE s.showId = :showId AND s.seatId = :seatId " +
            "AND (s.status = 'available' OR s.reservationId = :reservationId)")
    int updateSeatStatus(
            @Param("showId") UUID showId,
            @Param("seatId") String seatId,
            @Param("newStatus") String newStatus,
            @Param("reservationId") UUID reservationId);

    /**
     * Release a seat back to available — only if the reservation_id matches.
     * Prevents resurrecting a seat confirmed to someone else.
     */
    @Modifying
    @Query("UPDATE SeatEntity s SET s.status = 'available', s.reservationId = null " +
            "WHERE s.showId = :showId AND s.seatId = :seatId " +
            "AND s.reservationId = :reservationId")
    int releaseSeat(
            @Param("showId") UUID showId,
            @Param("seatId") String seatId,
            @Param("reservationId") UUID reservationId);
}
