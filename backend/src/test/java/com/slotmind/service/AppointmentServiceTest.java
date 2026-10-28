package com.slotmind.service;

import com.slotmind.dto.CreateAppointment;
import com.slotmind.entity.Appointment;
import com.slotmind.entity.Provider;
import com.slotmind.entity.Slot;
import com.slotmind.enums.AppointmentStatus;
import com.slotmind.enums.AppointmentType;
import com.slotmind.enums.ProviderStatus;
import com.slotmind.enums.SlotStatus;
import com.slotmind.enums.Specialty;
import com.slotmind.exception.ApiExceptions.Conflict;
import com.slotmind.repository.AppointmentRepository;
import com.slotmind.repository.ProviderRepository;
import com.slotmind.repository.SlotRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AppointmentServiceTest {
    @Mock
    private AppointmentRepository appointments;

    @Mock
    private ProviderRepository providers;

    @Mock
    private SlotRepository slots;

    @Mock
    private EntityManager entityManager;

    @Test
    void rejectsSlotThatIsAlreadyInThePast() {
        Provider provider = provider(ProviderStatus.AVAILABLE);
        Slot pastSlot = new Slot(
                "S1", provider, Instant.now().minusSeconds(60), 30, SlotStatus.OPEN, 0);
        when(slots.lockById("S1")).thenReturn(Optional.of(pastSlot));
        when(providers.lockById("P1")).thenReturn(Optional.of(provider));

        AppointmentService service = new AppointmentService(
                appointments, providers, slots, entityManager);

        CreateAppointment request = new CreateAppointment(
                "Patient", AppointmentType.ROUTINE, "P1", "S1");

        assertThrows(Conflict.class, () -> service.create(request));
        assertEquals(SlotStatus.OPEN, pastSlot.getStatus());
        verify(appointments, never()).save(any(Appointment.class));
    }

    @Test
    void booksOpenFutureSlotAndCreatesConfirmedAppointment() {
        Provider provider = provider(ProviderStatus.AVAILABLE);
        Slot slot = new Slot(
                "S1", provider, Instant.now().plusSeconds(3600), 30, SlotStatus.OPEN, 0);
        when(slots.lockById("S1")).thenReturn(Optional.of(slot));
        when(providers.lockById("P1")).thenReturn(Optional.of(provider));
        when(appointments.save(any(Appointment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AppointmentService service = new AppointmentService(
                appointments, providers, slots, entityManager);

        CreateAppointment request = new CreateAppointment(
                "Patient", AppointmentType.ROUTINE, "P1", "S1");
        Appointment created = service.create(request);

        assertEquals(SlotStatus.BOOKED, slot.getStatus());
        assertEquals(AppointmentStatus.CONFIRMED, created.getStatus());
        assertEquals("S1", created.getSlot().getId());
        verify(entityManager).refresh(provider, LockModeType.PESSIMISTIC_WRITE);
        verify(appointments).save(created);
    }

    private Provider provider(ProviderStatus status) {
        return new Provider("P1", "Provider", Specialty.GENERAL, status, 0);
    }
}
