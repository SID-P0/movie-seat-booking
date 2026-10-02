package com.seatbooking.common.entity;

import jakarta.persistence.*;
import lombok.*;
import java.util.UUID;

@Entity
@Table(name = "seats")
@IdClass(SeatId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SeatEntity {

    @Id
    @Column(name = "show_id")
    private UUID showId;

    @Id
    @Column(name = "seat_id")
    private String seatId;

    @Column(nullable = false)
    @Builder.Default
    private String status = "available";

    @Column(name = "reservation_id")
    private UUID reservationId;
}
