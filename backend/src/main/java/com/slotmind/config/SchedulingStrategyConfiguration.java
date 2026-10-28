package com.slotmind.config;

import com.slotmind.strategy.AISchedulingStrategy;
import com.slotmind.strategy.RuleBasedSchedulingStrategy;
import com.slotmind.strategy.SchedulingStrategy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.Locale;

@Configuration
public class SchedulingStrategyConfiguration {

    @Bean
    @Primary
    public SchedulingStrategy selectedSchedulingStrategy(
            @Value("${slotmind.scheduling.strategy:rule-based}") String configuredStrategy,
            RuleBasedSchedulingStrategy ruleBasedStrategy,
            AISchedulingStrategy aiSchedulingStrategy) {

        String strategy = configuredStrategy.trim().toLowerCase(Locale.ROOT);
        return switch (strategy) {
            case "rule-based", "rule_based", "rules" -> ruleBasedStrategy;
            case "ai", "ai-powered", "ai_powered" -> aiSchedulingStrategy;
            default -> throw new IllegalStateException(
                    "Unsupported slotmind.scheduling.strategy '" + configuredStrategy
                            + "'. Use 'rule-based' or 'ai'.");
        };
    }
}
