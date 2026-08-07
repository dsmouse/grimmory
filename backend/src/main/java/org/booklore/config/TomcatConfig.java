package org.booklore.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TomcatConfig {

    // Spring Boot 3+ deprecated server.max-http-header-size in favor of
    // server.max-http-request-header-size, which only governs request headers.
    // There is no property-based way to configure the response header buffer
    // on Tomcat anymore - it requires setting the connector attribute directly.
    // Default is 8KB, too small for a Kobo library sync response. See Arcana
    // incident 20260805-grimmory-kobo-sync-crash for the full investigation.
    @Value("${booklore.tomcat.max-http-response-header-size:2097152}")
    private int maxHttpResponseHeaderSize;

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> maxHttpResponseHeaderSizeCustomizer() {
        return factory -> factory.addConnectorCustomizers(connector ->
                connector.setProperty("maxHttpResponseHeaderSize", String.valueOf(maxHttpResponseHeaderSize)));
    }
}
