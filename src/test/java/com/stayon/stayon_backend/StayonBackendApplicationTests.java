package com.stayon.stayon_backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.aop.support.AopUtils;
import com.stayon.stayon_backend.service.ReservationSiteLinkService;
import com.stayon.stayon_backend.service.CustomerJwtTestProperties;
import javax.sql.DataSource;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@CustomerJwtTestProperties
@ActiveProfiles("test")
class StayonBackendApplicationTests {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ReservationSiteLinkService linkService;

	@Test
	void contextLoads() throws Exception {
		try (var connection = dataSource.getConnection()) {
			assertTrue(connection.getMetaData().getURL().startsWith("jdbc:h2:mem:"));
		}
		assertTrue(AopUtils.isAopProxy(linkService));
	}

}
