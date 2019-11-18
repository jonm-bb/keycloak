package org.keycloak.provider.quarkus;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import org.keycloak.services.resources.KeycloakApplication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class QuarkusLifecycle {

    private static final Logger LOGGER = LoggerFactory.getLogger("QuarkusLifecycle");

    private KeycloakApplication keycloakApplication;

    void onStart(@Observes StartupEvent ev) {
        LOGGER.info("Keycloak is starting...");
        keycloakApplication = new KeycloakApplication();
    }

    void onStop(@Observes ShutdownEvent ev) {
        LOGGER.info("Keycloak is stopping...");
        keycloakApplication.getSessionFactory().close();
    }

    public KeycloakApplication getKeycloakApplication() {
        return keycloakApplication;
    }

}