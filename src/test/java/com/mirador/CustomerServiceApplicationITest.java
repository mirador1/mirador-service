package com.mirador;

import org.junit.jupiter.api.Test;

class CustomerServiceApplicationITest extends AbstractIntegrationTest {

	@Test
	void contextLoads() {
		// Assertion: the Spring application context must start without errors.
		// This is a smoke test — if any bean fails to initialize (misconfigured
		// @Value, missing datasource, broken @PostConstruct, etc.) this test fails.
		// The assertion is implicit: Spring Boot @SpringBootTest raises an exception
		// if context startup fails, which JUnit records as a test error.
		org.junit.jupiter.api.Assertions.assertDoesNotThrow(
				() -> { /* context already loaded by @SpringBootTest — no-op */ });
	}

}
