package ai.khukuri.incident.service;

import ai.khukuri.incident.domain.Incident;
import ai.khukuri.incident.domain.IncidentStatus;
import ai.khukuri.incident.repository.IncidentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class IncidentLifecycleTest {

    @Autowired
    private IncidentService service;

    @Autowired
    private IncidentRepository incidents;

    @MockBean
    @SuppressWarnings("unused")
    private KafkaTemplate<String, String> kafka;

    /** ClickHouse is not reachable in unit tests and is not the subject here. */
    @MockBean(name = "clickhouseJdbc")
    @SuppressWarnings("unused")
    private JdbcTemplate clickhouseJdbc;

    private Incident open(String service_, String rule) {
        return service.openOrRefresh("retail-shop", service_, rule, "Title", "Summary",
                Incident.Severity.HIGH, 0.14, 0.05).orElseThrow();
    }

    @Test
    void opensThenMovesThroughItsLifecycle() {
        Incident incident = open("svc-lifecycle", "error-rate");
        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.OPEN);
        assertThat(incident.getReference()).startsWith("INC-");

        service.acknowledge(incident.getReference(), "prasiddha");
        assertThat(service.statusOf(incident.getReference())).isEqualTo(IncidentStatus.ACKNOWLEDGED);

        service.mitigate(incident.getReference());
        assertThat(service.statusOf(incident.getReference())).isEqualTo(IncidentStatus.MITIGATED);

        service.resolve(incident.getReference());
        assertThat(service.statusOf(incident.getReference())).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(incidents.findByReference(incident.getReference()).orElseThrow().getResolvedAt())
                .isNotNull();
    }

    @Test
    void aStillFiringRuleRefreshesInsteadOfOpeningADuplicate() {
        Incident first = open("svc-dedup", "error-rate");

        // A detector running every 30s over a long outage must not spam incidents.
        var second = service.openOrRefresh("retail-shop", "svc-dedup", "error-rate",
                "Title", "Summary", Incident.Severity.HIGH, 0.19, 0.05);
        var third = service.openOrRefresh("retail-shop", "svc-dedup", "error-rate",
                "Title", "Summary", Incident.Severity.HIGH, 0.22, 0.05);

        assertThat(second).isEmpty();
        assertThat(third).isEmpty();
        assertThat(incidents.findActive("retail-shop", "svc-dedup", "error-rate")).isPresent();
        // ...but the observed value tracks the latest reading.
        assertThat(incidents.findByReference(first.getReference()).orElseThrow().getObservedValue())
                .isEqualTo(0.22);
    }

    @Test
    void aResolvedConditionCanOpenAFreshIncidentLater() {
        Incident first = open("svc-recurring", "error-rate");
        service.resolve(first.getReference());

        var second = service.openOrRefresh("retail-shop", "svc-recurring", "error-rate",
                "Title", "Summary", Incident.Severity.HIGH, 0.30, 0.05);

        assertThat(second).isPresent();
        assertThat(second.get().getReference()).isNotEqualTo(first.getReference());
    }

    @Test
    void autoResolveClosesTheActiveIncidentAndIsSafeWhenNoneExists() {
        Incident incident = open("svc-auto", "error-rate");

        service.autoResolve("retail-shop", "svc-auto", "error-rate");
        assertThat(service.statusOf(incident.getReference())).isEqualTo(IncidentStatus.RESOLVED);

        // Called on every quiet pass — must be a no-op, not an error.
        service.autoResolve("retail-shop", "svc-auto", "error-rate");
    }

    @Test
    void acknowledgingATerminalIncidentIsRejected() {
        Incident incident = open("svc-illegal", "error-rate");
        service.resolve(incident.getReference());

        assertThatThrownBy(() -> service.acknowledge(incident.getReference(), "prasiddha"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RESOLVED");
    }

    @Test
    void resolvingTwiceIsIdempotentBecauseAutoAndManualResolveCanRace() {
        Incident incident = open("svc-idem", "error-rate");
        service.resolve(incident.getReference());
        service.resolve(incident.getReference());

        assertThat(service.statusOf(incident.getReference())).isEqualTo(IncidentStatus.RESOLVED);
    }

    @Test
    void differentRulesOnTheSameServiceAreSeparateIncidents() {
        Incident errorRate = open("svc-multi", "error-rate");
        Incident latency = open("svc-multi", "latency-p95");

        assertThat(errorRate.getReference()).isNotEqualTo(latency.getReference());
    }
}
