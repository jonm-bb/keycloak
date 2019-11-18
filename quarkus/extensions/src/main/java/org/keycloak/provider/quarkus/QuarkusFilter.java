package org.keycloak.provider.quarkus;

import io.vertx.core.http.HttpServerRequest;
import org.jboss.resteasy.core.interception.jaxrs.ContainerResponseContextImpl;
import org.keycloak.common.ClientConnection;
import org.keycloak.common.util.Resteasy;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.KeycloakTransaction;
import org.keycloak.services.resources.KeycloakApplication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerResponseContext;
import javax.ws.rs.container.PreMatching;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.NewCookie;
import javax.ws.rs.ext.Provider;
import java.io.IOException;
import java.net.HttpCookie;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@PreMatching
@Provider
public class QuarkusFilter implements javax.ws.rs.container.ContainerRequestFilter,
        javax.ws.rs.container.ContainerResponseFilter  {

    private static final Logger LOGGER = LoggerFactory.getLogger("QuarkusFilter");

    @Inject
    QuarkusLifecycle quarkusLifecycle;

    @Context
    HttpServerRequest request;

    @Override
    public void filter(ContainerRequestContext containerRequestContext) throws IOException {

        if (!containerRequestContext.getHeaders().containsKey("Content_Type")) {
            containerRequestContext.getHeaders().add("Content-Type", MediaType.APPLICATION_FORM_URLENCODED);
        }

        KeycloakApplication keycloakApplication = quarkusLifecycle.getKeycloakApplication();
        Resteasy.pushContext(KeycloakApplication.class, keycloakApplication);

        KeycloakSessionFactory sessionFactory = keycloakApplication.getSessionFactory();

        KeycloakSession session = sessionFactory.create();

        Resteasy.pushContext(KeycloakSession.class, session);
        ClientConnection connection = new ClientConnection() {
            @Override
            public String getRemoteAddr() {
                return request.remoteAddress().host();
            }

            @Override
            public String getRemoteHost() {
                return request.remoteAddress().host();
            }

            @Override
            public int getRemotePort() {
                return request.remoteAddress().port();
            }

            @Override
            public String getLocalAddr() {
                return request.localAddress().host();
            }

            @Override
            public int getLocalPort() {
                return request.localAddress().port();
            }
        };
        session.getContext().setConnection(connection);
        Resteasy.pushContext(ClientConnection.class, connection);

        KeycloakTransaction tx = session.getTransactionManager();
        Resteasy.pushContext(KeycloakTransaction.class, tx);
        tx.begin();
    }

    @Override
    public void filter(ContainerRequestContext containerRequestContext, ContainerResponseContext containerResponseContext) throws IOException {

        //Work around for https://github.com/vert-x3/vertx-web/issues/1340 (I think thats the issue, will need to investigate further)
        if (((ContainerResponseContextImpl) containerResponseContext).getHttpResponse().getOutputHeaders().get("Set-Cookie") != null
                &&  ((ContainerResponseContextImpl) containerResponseContext).getHttpResponse().getOutputHeaders().get("Set-Cookie").size() > 1) {
            ArrayList<String> list = (ArrayList) ((ContainerResponseContextImpl) containerResponseContext).getHttpResponse().getOutputHeaders().get("Set-Cookie");
            ArrayList<NewCookie> newCookies = new ArrayList<>();
            String keycloakSession = "";
            for (String item: list) {
                //NewCookie adds " around the cookie value for the KEYCLOAK_SESSION (maybe due to /), so skipping this one.
                if (item.contains("KEYCLOAK_SESSION")) {
                    keycloakSession = item;
                } else {
                    newCookies.add(cookieParser(item));
                }
            };

            list.clear();

            if (!keycloakSession.isEmpty()) {
                list.add(keycloakSession);
            }

            newCookies.forEach(item -> {
                ((ContainerResponseContextImpl) containerResponseContext).getHttpResponse().addNewCookie(item);
            });
        }

        //KeycloakTransactionCommitter not getting called, so adding here
        KeycloakTransaction tx = Resteasy.getContextData(KeycloakTransaction.class);
        if (tx != null && tx.isActive()) {
            if (tx.getRollbackOnly()) {
                tx.rollback();
            } else {
                tx.commit();
            }
        }

        //End the session and clear context
        KeycloakSession session = Resteasy.getContextData(KeycloakSession.class);

        session.close();
        Resteasy.clearContextData();
    }

    private NewCookie cookieParser(final String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }

        boolean zeroMaxAge = false;
        if(value.contains("Max-Age=0")) {
            zeroMaxAge = true;
        }

        boolean versionOne = false;
        if (value.contains("Version=1")) {
            versionOne = true;
        }

        final List<HttpCookie> httpCookies = HttpCookie.parse(value);
        final HttpCookie httpCookie = httpCookies.get(0);
        return new NewCookie(
                httpCookie.getName(),
                httpCookie.getValue(),
                httpCookie.getPath(),
                httpCookie.getDomain(),
                (versionOne) ? 1 : httpCookie.getVersion(),
                httpCookie.getComment(),
                (zeroMaxAge) ? 0 : (int) httpCookie.getMaxAge(),
                (zeroMaxAge) ? new Date(0) : (value.contains("Max-Age")) ? new Date(System.currentTimeMillis() + httpCookie.getMaxAge()*1000) : null,
                httpCookie.getSecure(),
                httpCookie.isHttpOnly());
    }
}
