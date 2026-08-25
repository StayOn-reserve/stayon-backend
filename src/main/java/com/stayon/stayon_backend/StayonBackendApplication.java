package com.stayon.stayon_backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class StayonBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(StayonBackendApplication.class, args);
	}

}
