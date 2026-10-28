package com.slotmind.dto;

import com.slotmind.enums.AppointmentType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateAppointment(
        @NotBlank String patientName,
        @NotNull AppointmentType type,
        @NotBlank String providerId,
        @NotBlank String slotId) {
}
