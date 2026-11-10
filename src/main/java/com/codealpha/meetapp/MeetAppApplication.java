package com.codealpha.meetapp;

import com.codealpha.meetapp.common.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
public class MeetAppApplication {

    public static void main(String[] args) {
        SpringApplication.run(MeetAppApplication.class, args);
    }
}
