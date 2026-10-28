package com.slotmind.service;

import com.slotmind.entity.Provider;
import com.slotmind.entity.Slot;
import com.slotmind.enums.ProviderStatus;
import com.slotmind.enums.SlotStatus;
import com.slotmind.enums.Specialty;
import com.slotmind.exception.ApiExceptions.Conflict;
import com.slotmind.repository.SlotRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SlotServiceTest {
    @Mock
    private SlotRepository slots;

    @Mock
    private ApplicationEventPublisher events;

    @Test
    void preventsManualReopeningOfBookedSlot() {
        Provider provider = new Provider(
                "P1", "Provider", Specialty.GENERAL, ProviderStatus.AVAILABLE, 0);
        Slot bookedSlot = new Slot(
                "S1", provider, Instant.now().plusSeconds(3_600), 30, SlotStatus.BOOKED, 0);
        when(slots.lockById("S1")).thenReturn(Optional.of(bookedSlot));

        SlotService service = new SlotService(slots, events);

        assertThrows(Conflict.class, () -> service.status("S1", SlotStatus.OPEN));
        verify(slots, never()).save(bookedSlot);
    }
}
