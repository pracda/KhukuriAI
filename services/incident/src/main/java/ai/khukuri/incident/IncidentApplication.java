package ai.khukuri.incident;

import ai.khukuri.incident.config.IncidentProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties(IncidentProperties.class)
@EnableScheduling
public class IncidentApplication {

    public static void main(String[] args) {
        SpringApplication.run(IncidentApplication.class, args);
    }
}
