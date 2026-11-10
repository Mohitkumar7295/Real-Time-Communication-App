package com.codealpha.meetapp.common.config;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppPropertiesTest {

    private Validator validator;

    @BeforeEach
    void setUp() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            this.validator = factory.getValidator();
        }
    }

    @Test
    @DisplayName("Valid default configuration passes validation and post-construct")
    void validDefaultConfiguration_passes() {
        AppProperties properties = new AppProperties();
        properties.validateOrigin();

        var violations = validator.validate(properties);
        assertThat(violations).isEmpty();
        assertThat(properties.getAllowedOrigin()).isEqualTo("http://localhost:8080");
        assertThat(properties.getMaxFileSizeMb()).isEqualTo(10);
        assertThat(properties.getMaxRoomParticipants()).isEqualTo(5);
        assertThat(properties.getOtpExpiryMinutes()).isEqualTo(5);
    }

    @Test
    @DisplayName("Wildcard origin is rejected by validation and post-construct check")
    void wildcardOrigin_rejected() {
        AppProperties properties = new AppProperties();
        properties.setAllowedOrigin("*");

        var violations = validator.validate(properties);
        assertThat(violations).isNotEmpty();

        assertThatThrownBy(properties::validateOrigin)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must never be wildcard '*'");
    }

    @Test
    @DisplayName("Origin with trailing slash is rejected")
    void trailingSlashOrigin_rejected() {
        AppProperties properties = new AppProperties();
        properties.setAllowedOrigin("https://meetapp.com/");

        var violations = validator.validate(properties);
        assertThat(violations).isNotEmpty();

        assertThatThrownBy(properties::validateOrigin)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("trailing slash");
    }

    @Test
    @DisplayName("Origin without http/https scheme is rejected")
    void nonHttpScheme_rejected() {
        AppProperties properties = new AppProperties();
        properties.setAllowedOrigin("ftp://meetapp.com");

        var violations = validator.validate(properties);
        assertThat(violations).isNotEmpty();

        assertThatThrownBy(properties::validateOrigin)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("http or https scheme");
    }

    @Test
    @DisplayName("Non-positive numeric properties fail validation")
    void nonPositiveNumericProperties_failValidation() {
        AppProperties properties = new AppProperties();
        properties.setMaxFileSizeMb(0);
        properties.setMaxRoomParticipants(-1);
        properties.setOtpExpiryMinutes(0);

        var violations = validator.validate(properties);
        assertThat(violations).hasSize(3);
    }

    @Test
    @DisplayName("toString() masks turnCredential and does not leak raw secrets")
    void toString_masksTurnCredential() {
        AppProperties properties = new AppProperties();
        properties.setTurnCredential("super-secret-turn-token");
        properties.setTurnUsername("turnuser");
        properties.setTurnUrl("turn:turn.meetapp.com:3478");

        String str = properties.toString();

        assertThat(str).doesNotContain("super-secret-turn-token");
        assertThat(str).contains("turnCredential='****'");
        assertThat(str).contains("turnUsername='turnuser'");
        assertThat(str).contains("allowedOrigin='http://localhost:8080'");
    }
}
