package ai.khukuri.incident.service;

import ai.khukuri.incident.config.IncidentProperties;
import ai.khukuri.incident.domain.Incident;
import ai.khukuri.incident.domain.IncidentStatus;
import ai.khukuri.incident.repository.IncidentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Incident lifecycle plus the {@code incidents.events} stream other services consume. */
@Service
public class IncidentService {

    private static final Logger log = LoggerFactory.getLogger(IncidentService.class);

    private final IncidentRepository incidents;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;
    private final IncidentProperties props;

    public IncidentService(IncidentRepository incidents, KafkaTemplate<String, String> kafka,
                           ObjectMapper mapper, IncidentProperties props) {
        this.incidents = incidents;
        this.kafka = kafka;
        this.mapper = mapper;
        this.props = props;
    }

    /**
     * Opens an incident, or refreshes the one already tracking this condition.
     *
     * <p>Dedup matters more than it looks: a detector running every 30 seconds against a
     * failure lasting an hour would otherwise open 120 incidents for one outage.
     */
    @Transactional
    public Optional<Incident> openOrRefresh(String tenantId, String serviceName, String ruleKey,
                                            String title, String summary,
                                            Incident.Severity severity,
                                            double observed, double threshold) {
        Optional<Incident> existing = incidents.findActive(tenantId, serviceName, ruleKey);
        if (existing.isPresent()) {
            existing.get().refreshObservation(observed);
            return Optional.empty();
        }

        Incident incident = new Incident(nextReference(), tenantId, serviceName, ruleKey,
                title, summary, severity, observed, threshold);
        try {
            incidents.save(incident);
        } catch (DataIntegrityViolationException e) {
            // The partial unique index caught a concurrent detector pass — that is the
            // index doing its job, not an error worth surfacing.
            log.debug("Concurrent open for {}/{}/{} collapsed by the unique index",
                    tenantId, serviceName, ruleKey);
            return Optional.empty();
        }

        log.warn("INCIDENT OPENED {} — {} [{}] {} (observed {} vs threshold {})",
                incident.getReference(), tenantId, serviceName, title, observed, threshold);
        publish("opened", incident);
        return Optional.of(incident);
    }

    /** Called when a rule stops firing: the condition cleared on its own. */
    @Transactional
    public void autoResolve(String tenantId, String serviceName, String ruleKey) {
        incidents.findActive(tenantId, serviceName, ruleKey).ifPresent(incident -> {
            incident.resolve();
            log.info("INCIDENT AUTO-RESOLVED {} — condition cleared", incident.getReference());
            publish("resolved", incident);
        });
    }

    @Transactional
    public Incident acknowledge(String reference, String username) {
        Incident incident = require(reference);
        incident.acknowledge(username);
        publish("acknowledged", incident);
        return incident;
    }

    @Transactional
    public Incident mitigate(String reference) {
        Incident incident = require(reference);
        incident.mitigate();
        publish("mitigated", incident);
        return incident;
    }

    @Transactional
    public Incident resolve(String reference) {
        Incident incident = require(reference);
        incident.resolve();
        publish("resolved", incident);
        return incident;
    }

    private Incident require(String reference) {
        return incidents.findByReference(reference)
                .orElseThrow(() -> new IllegalArgumentException("No such incident: " + reference));
    }

    /** INC-1, INC-2, … — readable in a chat window, unlike a UUID. */
    private String nextReference() {
        return "INC-" + (incidents.countAll() + 1);
    }

    private void publish(String eventType, Incident incident) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event", eventType);
        event.put("reference", incident.getReference());
        event.put("tenant_id", incident.getTenantId());
        event.put("service_name", incident.getServiceName());
        event.put("rule_key", incident.getRuleKey());
        event.put("title", incident.getTitle());
        event.put("severity", incident.getSeverity().name());
        event.put("status", incident.getStatus().name());
        event.put("observed_value", incident.getObservedValue());
        event.put("occurred_at", Instant.now().toString());
        try {
            kafka.send(props.kafka().topic(), incident.getTenantId(), mapper.writeValueAsString(event));
        } catch (Exception e) {
            // A failed notification must not roll back a real incident record.
            log.error("Could not publish {} event for {}", eventType, incident.getReference(), e);
        }
    }

    public IncidentStatus statusOf(String reference) {
        return require(reference).getStatus();
    }
}
