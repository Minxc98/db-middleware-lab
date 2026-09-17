package com.pacvue.lab.mysql.support;

import com.pacvue.lab.mysql.repository.OrderJdbcRepository;
import com.pacvue.lab.mysql.service.OrderSeedService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base class for tests about the application itself (probe, seeding, repository), as opposed to
 * the InnoDB behaviour suite.
 *
 * <p>Points the Boot context at whatever {@link MysqlContainerFactory} resolves - a managed
 * container or a server you already have up - and gives each test a freshly created table.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractMysqlIT {

    @Autowired
    protected OrderJdbcRepository repository;

    @Autowired
    protected OrderSeedService seeder;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MysqlContainerFactory::jdbcUrl);
        registry.add("spring.datasource.username", MysqlContainerFactory::username);
        registry.add("spring.datasource.password", MysqlContainerFactory::password);
    }

    @BeforeEach
    void resetTable() {
        repository.recreateTable();
    }
}
