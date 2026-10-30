package com.meguri.core.capability;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/** No new default network exposure; deployment opt-in and existing turn permission are both required. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "meguri.web-fetch.enabled", havingValue = "true")
public class PublicWebFetchConfiguration {
    @Bean
    PublicWebFetchCapability publicWebFetchCapability(CapabilityRuntimeFacade runtime) {
        PublicWebFetchCapability capability = new PublicWebFetchCapability(new PublicWebFetcher());
        runtime.register(PublicWebFetchCapability.descriptor(), capability);
        return capability;
    }
}
