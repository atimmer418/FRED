package com.investingapp.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.rest.RepositoryRestMvcAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

@SpringBootApplication(exclude = RepositoryRestMvcAutoConfiguration.class)
@EnableScheduling
public class BackendApplication {

	public static void main(String[] args) {
		// Decision 17: the server runs on Eastern time. Money paths and notifications still name
		// EasternTime.ZONE explicitly; this pin only keeps bare clocks elsewhere on the same day.
		TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
		System.setProperty("user.timezone", "America/New_York");
		
		SpringApplication.run(BackendApplication.class, args);
	}

}
