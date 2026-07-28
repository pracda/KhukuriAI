package ai.khukuri.incident.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * This service reads from two stores, so both are declared explicitly rather than
 * relying on single-datasource autoconfiguration:
 *
 * <ul>
 *   <li><b>Postgres</b> (primary) — incidents and deployments. JPA and Flyway bind here.
 *   <li><b>ClickHouse</b> — telemetry, queried read-only through its own JdbcTemplate.
 * </ul>
 */
@Configuration
public class DataSourceConfig {

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties postgresProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    public DataSource dataSource(DataSourceProperties postgresProperties) {
        return postgresProperties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    @Bean
    @ConfigurationProperties("clickhouse.datasource")
    public DataSourceProperties clickhouseProperties() {
        return new DataSourceProperties();
    }

    @Bean
    public DataSource clickhouseDataSource(
            @Qualifier("clickhouseProperties") DataSourceProperties clickhouseProperties) {
        return clickhouseProperties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    @Bean
    public JdbcTemplate clickhouseJdbc(@Qualifier("clickhouseDataSource") DataSource clickhouse) {
        return new JdbcTemplate(clickhouse);
    }
}
