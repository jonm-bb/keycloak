package org.keycloak.provider.quarkus;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.inject.Inject;

import io.quarkus.runtime.ShutdownEvent;
import org.keycloak.services.resources.KeycloakApplication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class QuarkusShutdownObserver {

    private static final Logger LOGGER = LoggerFactory.getLogger("QuarkusShutdownObserver");

    @Inject
    private KeycloakApplication keycloakApplication;

    void onStop(@Observes ShutdownEvent ev) {
        LOGGER.info("Keycloak is stopping...");
        keycloakApplication.getSessionFactory().close();
    }
}