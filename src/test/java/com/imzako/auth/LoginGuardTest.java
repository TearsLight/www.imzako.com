package com.imzako.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoginGuardTest {
    @Test
    void requiresACaptchaOnlyAfterTheConfiguredNumberOfFailures() {
        var guard = new LoginGuard(3, 900);

        assertThat(guard.threshold()).isEqualTo(3);
        assertThat(guard.captchaRequired("member@example.com", "10.0.0.1")).isFalse();

        guard.recordFailure("member@example.com", "10.0.0.1");
        guard.recordFailure("member@example.com", "10.0.0.1");
        assertThat(guard.captchaRequired("member@example.com", "10.0.0.1")).isFalse();

        guard.recordFailure("member@example.com", "10.0.0.1");
        assertThat(guard.captchaRequired("member@example.com", "10.0.0.1")).isTrue();
    }

    @Test
    void aSuccessfulSignInClearsTheFailureCount() {
        var guard = new LoginGuard(3, 900);
        guard.recordFailure("member@example.com", "10.0.0.1");
        guard.recordFailure("member@example.com", "10.0.0.1");

        guard.recordSuccess("member@example.com", "10.0.0.1");

        assertThat(guard.captchaRequired("member@example.com", "10.0.0.1")).isFalse();
    }

    @Test
    void unrelatedIdentitiesAndAddressesDoNotShareCounters() {
        var guard = new LoginGuard(3, 900);
        guard.recordFailure("first@example.com", "10.0.0.1");
        guard.recordFailure("second@example.com", "10.0.0.2");
        guard.recordFailure("third@example.com", "10.0.0.3");

        assertThat(guard.captchaRequired("fourth@example.com", "10.0.0.4")).isFalse();
        // Each bucket has one failure, so neither identity nor address is blocked.
        assertThat(guard.captchaRequired("first@example.com", "10.0.0.9")).isFalse();
    }

    @Test
    void failuresAccumulateAcrossRotatingIdentitiesFromOneAddress() {
        var guard = new LoginGuard(3, 900);
        guard.recordFailure("a@example.com", "10.0.0.1");
        guard.recordFailure("b@example.com", "10.0.0.1");
        guard.recordFailure("c@example.com", "10.0.0.1");

        // The address bucket pins the attacker even though every identity is new.
        assertThat(guard.captchaRequired("brand-new@example.com", "10.0.0.1")).isTrue();
    }

    @Test
    void identityComparisonIgnoresCaseAndSurroundingSpace() {
        var guard = new LoginGuard(2, 900);
        guard.recordFailure(" Member@Example.com ", "10.0.0.1");
        guard.recordFailure("member@example.com", "10.0.0.1");

        assertThat(guard.captchaRequired("MEMBER@EXAMPLE.COM", "10.0.0.1")).isTrue();
    }

    @Test
    void missingIdentityOrAddressStillProducesAUsableBucket() {
        var guard = new LoginGuard(2, 900);

        guard.recordFailure(null, null);
        guard.recordFailure(null, null);

        assertThat(guard.captchaRequired(null, null)).isTrue();
    }

    @Test
    void attackerControlledIdentitiesCannotGrowTheCounterMapWithoutBound() {
        var guard = new LoginGuard(3, 900, 100);

        for (int i = 0; i < 250; i++) {
            guard.recordFailure("user-" + i + "@example.com", "10.0.0." + i);
        }

        assertThat(guard.trackedKeys()).isLessThanOrEqualTo(100);
    }
}
