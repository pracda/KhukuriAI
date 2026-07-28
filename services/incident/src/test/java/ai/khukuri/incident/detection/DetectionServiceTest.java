package ai.khukuri.incident.detection;

import ai.khukuri.incident.config.IncidentProperties;
import ai.khukuri.incident.domain.Incident;
import ai.khukuri.incident.service.IncidentService;
import ai.khukuri.incident.telemetry.TelemetryQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Detection logic against a stubbed telemetry layer — thresholds, the auto-resolve half,
 * and severity escalation, with no database or container involved.
 */
class DetectionServiceTest {

    private TelemetryQueryService telemetry;
    private IncidentService incidents;
    private DetectionService detection;

    @BeforeEach
    void setUp() {
        telemetry = mock(TelemetryQueryService.class);
        incidents = mock(IncidentService.class);
        detection = new DetectionService(properties(), telemetry, incidents);

        // Default: nothing firing anywhere unless a test says otherwise.
        when(telemetry.serviceHealth(anyString(), anyInt())).thenReturn(List.of());
        when(telemetry.latencyByService(anyString(), anyInt())).thenReturn(Map.of());
        when(telemetry.latestMetricByService(anyString(), anyString(), anyInt())).thenReturn(Map.of());
    }

    private IncidentProperties properties() {
        return new IncidentProperties(
                new IncidentProperties.Detection(
                        true, Duration.ofMinutes(5), List.of("retail-shop"),
                        new IncidentProperties.ErrorRate(true, 0.05, 10),
                        new IncidentProperties.Latency(true, 2000, 10),
                        List.of(new IncidentProperties.Saturation("db.pool.active", 9, "Pool full."))),
                new IncidentProperties.Kafka("incidents.events"));
    }

    @Test
    void opensAnIncidentWhenErrorRateCrossesTheThreshold() {
        when(telemetry.serviceHealth("retail-shop", 300)).thenReturn(List.of(
                new TelemetryQueryService.ServiceHealth("retail-shop", 100, 14, 0.14)));

        detection.evaluateTenant("retail-shop");

        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        // 14% against a 5% threshold is 2.8x over — HIGH; CRITICAL starts at 3x.
        verify(incidents).openOrRefresh(eq("retail-shop"), eq("retail-shop"),
                eq(DetectionService.RULE_ERROR_RATE), anyString(), summary.capture(),
                eq(Incident.Severity.HIGH), eq(0.14), eq(0.05));
        // The summary is what a human reads first — it must carry the actual numbers.
        assertThat(summary.getValue()).contains("14.0%").contains("14 of 100");
    }

    @Test
    void doesNotFireOnATinySampleEvenAtAHighRate() {
        // 2 of 3 requests failing is 66%, but three requests prove nothing.
        when(telemetry.serviceHealth("retail-shop", 300)).thenReturn(List.of(
                new TelemetryQueryService.ServiceHealth("retail-shop", 3, 2, 0.66)));

        detection.evaluateTenant("retail-shop");

        verify(incidents, never()).openOrRefresh(anyString(), anyString(), anyString(),
                anyString(), anyString(), org.mockito.ArgumentMatchers.any(), anyDouble(), anyDouble());
        verify(incidents).autoResolve("retail-shop", "retail-shop", DetectionService.RULE_ERROR_RATE);
    }

    @Test
    void autoResolvesWhenTheConditionClears() {
        when(telemetry.serviceHealth("retail-shop", 300)).thenReturn(List.of(
                new TelemetryQueryService.ServiceHealth("retail-shop", 500, 1, 0.002)));

        detection.evaluateTenant("retail-shop");

        verify(incidents).autoResolve("retail-shop", "retail-shop", DetectionService.RULE_ERROR_RATE);
        verify(incidents, never()).openOrRefresh(anyString(), anyString(), anyString(),
                anyString(), anyString(), org.mockito.ArgumentMatchers.any(), anyDouble(), anyDouble());
    }

    @Test
    void firesOnSlowLatency() {
        when(telemetry.latencyByService("retail-shop", 300)).thenReturn(Map.of(
                "retail-shop", new TelemetryQueryService.LatencyStat(4500, 120)));

        detection.evaluateTenant("retail-shop");

        verify(incidents).openOrRefresh(eq("retail-shop"), eq("retail-shop"),
                eq(DetectionService.RULE_LATENCY), anyString(), anyString(),
                eq(Incident.Severity.HIGH), eq(4500.0), eq(2000.0));
    }

    @Test
    void firesOnSaturationOfAConfiguredMetric() {
        when(telemetry.latestMetricByService("retail-shop", "db.pool.active", 300))
                .thenReturn(Map.of("retail-shop", 10.0));

        detection.evaluateTenant("retail-shop");

        verify(incidents).openOrRefresh(eq("retail-shop"), eq("retail-shop"),
                eq("saturation:db.pool.active"), anyString(), anyString(),
                org.mockito.ArgumentMatchers.any(), eq(10.0), eq(9.0));
    }

    @Test
    void severityScalesWithHowFarPastTheThreshold() {
        assertThat(DetectionService.severityFor(0.06, 0.05)).isEqualTo(Incident.Severity.LOW);
        assertThat(DetectionService.severityFor(0.07, 0.05)).isEqualTo(Incident.Severity.MEDIUM);
        assertThat(DetectionService.severityFor(0.10, 0.05)).isEqualTo(Incident.Severity.HIGH);
        assertThat(DetectionService.severityFor(0.20, 0.05)).isEqualTo(Incident.Severity.CRITICAL);
    }

    @Test
    void aFailingTelemetryLayerDoesNotKillTheDetector() {
        when(telemetry.activeTenants(anyInt())).thenThrow(new RuntimeException("ClickHouse down"));
        DetectionService sweeping = new DetectionService(
                new IncidentProperties(
                        new IncidentProperties.Detection(true, Duration.ofMinutes(5), List.of(),
                                new IncidentProperties.ErrorRate(true, 0.05, 10),
                                new IncidentProperties.Latency(true, 2000, 10), List.of()),
                        new IncidentProperties.Kafka("incidents.events")),
                telemetry, incidents);

        // Must not propagate — the scheduler would stop calling a method that throws.
        sweeping.evaluate();
    }
}
