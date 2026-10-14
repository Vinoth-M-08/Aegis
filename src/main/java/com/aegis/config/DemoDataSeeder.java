package com.aegis.config;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.aegis.auth.ApiKeyAuthenticator;
import com.aegis.auth.Tenant;
import com.aegis.auth.TenantRepository;
import com.aegis.auth.TenantSettings;

/**
 * Creates a demo tenant and the README's example key so the gateway can be tried immediately.
 * Only active when {@code aegis.demo.seed=true}; never enable it in production.
 */
@Component
@ConditionalOnProperty(name = "aegis.demo.seed", havingValue = "true")
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);
    private static final String TENANT_NAME = "demo";

    private final TenantRepository tenants;
    private final AegisProperties properties;

    public DemoDataSeeder(TenantRepository tenants, AegisProperties properties) {
        this.tenants = tenants;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (tenants.findByName(TENANT_NAME).isPresent()) {
            return;
        }
        Tenant tenant = tenants.create(TenantSettings.defaults(TENANT_NAME));
        ApiKeyAuthenticator.GeneratedKey key = ApiKeyAuthenticator.GeneratedKey.of(properties.demo().apiKey());
        tenants.createKey(tenant.id(), "demo key", key.displayPrefix(), key.hash(), List.of(), null);
        log.warn("Seeded demo tenant '{}' with API key '{}'. Disable aegis.demo.seed outside local development.",
                TENANT_NAME, properties.demo().apiKey());
    }
}
