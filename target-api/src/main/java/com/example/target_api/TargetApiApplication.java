package com.example.target_api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TargetApiApplication {

	public static void main(String[] args) {
		SpringApplication.run(TargetApiApplication.class, args);
	}

}
