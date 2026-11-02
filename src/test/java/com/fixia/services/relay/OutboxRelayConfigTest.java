package com.fixia.services.relay;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxRelayConfigTest {

    @Test
    void configInstancia() {
        OutboxRelayConfig config = new OutboxRelayConfig();
        assertThat(config).isNotNull();
    }
}

