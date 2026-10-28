package com.slotmind.strategy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slotmind.entity.Appointment;
import com.slotmind.entity.Provider;
import com.slotmind.entity.Slot;
import com.slotmind.enums.AppointmentType;
import com.slotmind.enums.TriggerReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class LiteLLMGateway implements LLMGateway {
    private static final Logger log = LoggerFactory.getLogger(LiteLLMGateway.class);
    private static final String SYSTEM_PROMPT = """
            You are a careful appointment rescheduling advisor.
            Recommend only a slot from the supplied eligible slot list.
            Never invent slot or provider IDs. Do not make medical decisions.
            Return only the requested JSON object. The explanation must be short,
            factual, and suitable for front-desk staff to read to a patient.
            Do not include a clock time or timezone in the explanation; the application
            displays the verified appointment time separately.
            """;

    private final RestClient client;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String model;
    private final String product;
    private final String cookie;

    public LiteLLMGateway(
            RestClient.Builder builder,
            ObjectMapper objectMapper,
            Environment environment) {
        String baseUrl = value(
                environment, "SLOTMIND_LLM_BASE_URL", "https://litellm-qc.zycus.net/v1");
        this.apiKey = value(environment, "SLOTMIND_LLM_API_KEY", "");
        this.model = value(environment, "SLOTMIND_LLM_MODEL", "qwen-cursor");
        this.product = value(environment, "SLOTMIND_LLM_PRODUCT", "PC1");
        this.cookie = value(environment, "SLOTMIND_LLM_COOKIE", "");
        this.objectMapper = objectMapper;

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(3_000);
        requestFactory.setReadTimeout(8_000);
        this.client = builder.baseUrl(baseUrl).requestFactory(requestFactory).build();
    }

    @Override
    public Optional<Recommendation> recommend(
            Appointment appointment,
            TriggerReason reason,
            List<Slot> candidates) {
        if (apiKey.isBlank() || candidates.isEmpty()) {
            return Optional.empty();
        }

        try {
            ChatRequest request = new ChatRequest(model, List.of(
                    new Message("system", SYSTEM_PROMPT),
                    new Message("user", prompt(appointment, reason, candidates))));

            RestClient.RequestHeadersSpec<?> call = client.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("product", product)
                    .headers(headers -> {
                        if (!cookie.isBlank()) {
                            headers.set("Cookie", cookie);
                        }
                    })
                    .body(request);

            JsonNode response = call.retrieve().body(JsonNode.class);
            return parse(response);
        } catch (Exception exception) {
            log.warn(
                    "LLM scheduling request failed; using rule-based fallback: {}",
                    exception.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    static String prompt(
            Appointment appointment,
            TriggerReason reason,
            List<Slot> candidates) {
        String urgency = appointment.getType() == AppointmentType.URGENT ? "HIGH" : "NORMAL";
        StringBuilder prompt = new StringBuilder();

        prompt.append("Trigger: ").append(reason).append(".\n")
                .append("Appointment context: appointmentId=").append(appointment.getId())
                .append(", type=").append(appointment.getType())
                .append(", urgency=").append(urgency)
                .append(", currentStatus=").append(appointment.getStatus())
                .append(".\n")
                .append("Original booking: slotId=").append(appointment.getSlot().getId())
                .append(", startTime=").append(appointment.getSlot().getStartTime())
                .append(", durationMinutes=").append(appointment.getSlot().getDurationMinutes())
                .append(".\n")
                .append("Original provider: providerId=").append(appointment.getProvider().getId())
                .append(", specialty=").append(appointment.getProvider().getSpecialty())
                .append(", status=").append(appointment.getProvider().getStatus())
                .append(".\n");

        if (reason == TriggerReason.PROVIDER_OFFLINE) {
            prompt.append("The original provider is offline. Do not recommend that provider. ")
                    .append("Choose an eligible alternative provider.\n");
        } else if (reason == TriggerReason.SLOT_CANCELLED) {
            prompt.append("The original slot was cancelled. Prefer the same eligible provider's ")
                    .append("nearest open slot when possible; otherwise choose an eligible alternative.\n");
        } else {
            prompt.append("This is a manual rescheduling request. Prefer the same provider's ")
                    .append("nearest open slot, then a matching specialty.\n");
        }

        Map<String, Provider> roster = new LinkedHashMap<>();
        for (Slot slot : candidates) {
            roster.putIfAbsent(slot.getProvider().getId(), slot.getProvider());
        }

        prompt.append("Eligible provider roster (providers with at least one eligible open slot):\n");
        for (Provider provider : roster.values()) {
            prompt.append("- providerId=").append(provider.getId())
                    .append(", name=").append(provider.getName())
                    .append(", specialty=").append(provider.getSpecialty())
                    .append(", status=").append(provider.getStatus()).append('\n');
        }

        prompt.append("Eligible candidate slots (select exactly one slotId):\n");
        for (Slot slot : candidates) {
            prompt.append("- slotId=").append(slot.getId())
                    .append(", providerId=").append(slot.getProvider().getId())
                    .append(", providerSpecialty=").append(slot.getProvider().getSpecialty())
                    .append(", startTime=").append(slot.getStartTime())
                    .append(", durationMinutes=").append(slot.getDurationMinutes()).append('\n');
        }

        prompt.append("""
                Return exactly this JSON shape:
                {"slotId":"actual candidate ID","alternateProviderId":"actual provider ID or null",
                "confidence":0.75,"reasoning":"short patient-friendly explanation"}
                Confidence must be a positive value from 0.01 to 1.0. Do not copy the example value blindly.
                """);
        return prompt.toString();
    }

    private Optional<Recommendation> parse(JsonNode response) {
        try {
            if (response == null) {
                return Optional.empty();
            }

            JsonNode content = response.path("choices").path(0).path("message").path("content");
            if (!content.isTextual()) {
                return Optional.empty();
            }

            String json = content.asText().trim();
            if (json.startsWith("```")) {
                json = json.replaceFirst("^```(?:json)?\\s*", "")
                        .replaceFirst("\\s*```$", "")
                        .trim();
            }

            JsonNode result = objectMapper.readTree(json);
            if (!result.isObject()
                    || !result.hasNonNull("slotId")
                    || !result.has("confidence")
                    || !result.hasNonNull("reasoning")) {
                return Optional.empty();
            }

            String alternate = result.path("alternateProviderId").isNull()
                    || result.path("alternateProviderId").isMissingNode()
                    ? null
                    : result.path("alternateProviderId").asText(null);

            return Optional.of(new Recommendation(
                    result.path("slotId").asText(),
                    alternate,
                    result.path("confidence").asDouble(Double.NaN),
                    result.path("reasoning").asText()));
        } catch (Exception exception) {
            return Optional.empty();
        }
    }

    private static String value(Environment environment, String key, String fallback) {
        String value = environment.getProperty(key);
        return value == null ? fallback : value.trim();
    }

    private record Message(String role, String content) {
    }

    private record ChatRequest(String model, List<Message> messages) {
    }
}
