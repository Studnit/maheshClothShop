package com.maheshclothes.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI shopOpenApi() {
        return new OpenAPI()
                .info(new Info().title("Mahesh Clothes Shop API").version("v1")
                        .description("Read-only integration API. Sign in with HTTP Basic using a shop user; "
                                + "the user's permissions decide what is returned."))
                .components(new Components().addSecuritySchemes("basicAuth",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("basic")));
    }
}
