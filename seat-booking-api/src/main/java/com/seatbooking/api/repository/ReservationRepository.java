package com.seatbooking.api.repository;

import com.seatbooking.common.entity.ReservationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReservationRepository extends JpaRepository<ReservationEntity, UUID> {

    Optional<ReservationEntity> findByIdempotencyKey(String idempotencyKey);

    @Query("SELECT r FROM ReservationEntity r WHERE r.userId = :userId AND r.showId = :showId AND r.status IN ('confirmed', 'held')")
    List<ReservationEntity> findActiveByUserIdAndShowId(@Param("userId") UUID userId,
                                                         @Param("showId") UUID showId);

    @Query("SELECT r FROM ReservationEntity r WHERE r.status IN ('confirmed', 'held')")
    List<ReservationEntity> findAllActive();
}
