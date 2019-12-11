/*
 * Copyright 2019 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.keycloak.services.filters;

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
    KeycloakApplication keycloakApplication;

    @Context
    HttpServerRequest request;

    @Override
    public void filter(ContainerRequestContext containerRequestContext) throws IOException {

        if (containerRequestContext.getMediaType() == null) {
            containerRequestContext.getHeaders().add("Content-Type", MediaType.APPLICATION_FORM_URLENCODED);
        }

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

        //TODO
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

        //KeycloakTransactionCommitter does not have RestEasy context for some resason, so running code here
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

        NewCookie cookie = NewCookie.valueOf(value);

        String cookieValue = cookie.getValue();

        return cookie;
    }
}