package no.novari.qliktosharepoint;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@ConfigurationPropertiesScan
@SpringBootApplication
public class QlikToSharepointApplication {

    public static void main(String[] args) {
        SpringApplication.run(QlikToSharepointApplication.class, args);
    }

}
