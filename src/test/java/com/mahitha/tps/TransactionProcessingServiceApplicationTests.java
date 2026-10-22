package com.mahitha.tps;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:context-test;DB_CLOSE_DELAY=-1", "processing.enabled=false"})
class TransactionProcessingServiceApplicationTests {

	@Test
	void contextLoads() {
	}

}
