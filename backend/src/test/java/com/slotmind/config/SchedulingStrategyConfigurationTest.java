package com.slotmind.config;

import com.slotmind.strategy.AISchedulingStrategy;
import com.slotmind.strategy.RuleBasedSchedulingStrategy;
import com.slotmind.strategy.SchedulingStrategy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class SchedulingStrategyConfigurationTest {

    private final SchedulingStrategyConfiguration configuration = new SchedulingStrategyConfiguration();
    private final RuleBasedSchedulingStrategy ruleBased = mock(RuleBasedSchedulingStrategy.class);
    private final AISchedulingStrategy ai = mock(AISchedulingStrategy.class);

    @Test
    void selectsRuleBasedStrategyForDefaultConfiguration() {
        SchedulingStrategy selected =
                configuration.selectedSchedulingStrategy("rule-based", ruleBased, ai);

        assertSame(ruleBased, selected);
    }

    @Test
    void selectsAiStrategyWhenExplicitlyConfigured() {
        SchedulingStrategy selected =
                configuration.selectedSchedulingStrategy("AI", ruleBased, ai);

        assertSame(ai, selected);
    }

    @Test
    void rejectsUnknownStrategyConfiguration() {
        assertThrows(
                IllegalStateException.class,
                () -> configuration.selectedSchedulingStrategy("unknown", ruleBased, ai));
    }
}
