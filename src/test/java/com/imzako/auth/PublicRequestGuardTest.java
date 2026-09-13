package com.imzako.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PublicRequestGuardTest {
    @Test
    void limitsRegistrationRequestsPerAddress() {
        var guard = new PublicRequestGuard(2, 2, 2, 100);

        guard.registration("10.0.0.1");
        guard.registration("10.0.0.1");

        assertThatThrownBy(() -> guard.registration("10.0.0.1"))
                .isInstanceOf(PublicRateLimitException.class);
        guard.registration("10.0.0.2");
    }

    @Test
    void attackerControlledAddressesCannotGrowTheBucketMapWithoutBound() {
        var guard = new PublicRequestGuard(2, 2, 2, 100);

        for (int i = 0; i < 250; i++) {
            guard.registration("10.0.0." + i);
        }

        assertThat(guard.trackedKeys()).isLessThanOrEqualTo(100);
    }
}
