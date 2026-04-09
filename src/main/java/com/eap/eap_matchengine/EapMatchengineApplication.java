package com.eap.eap_matchengine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class EapMatchengineApplication {

	public static void main(String[] args) {
		SpringApplication.run(EapMatchengineApplication.class, args);
	}

}
