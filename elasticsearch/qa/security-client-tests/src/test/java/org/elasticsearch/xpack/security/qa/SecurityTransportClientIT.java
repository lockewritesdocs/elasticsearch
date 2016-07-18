/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.security.qa;

import org.elasticsearch.ElasticsearchSecurityException;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthResponse;
import org.elasticsearch.action.admin.cluster.node.info.NodeInfo;
import org.elasticsearch.action.admin.cluster.node.info.NodesInfoResponse;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.transport.MockTransportClient;
import org.elasticsearch.xpack.security.Security;
import org.elasticsearch.xpack.security.authc.support.SecuredString;
import org.elasticsearch.test.ESIntegTestCase;
import org.elasticsearch.xpack.XPackPlugin;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.elasticsearch.xpack.security.authc.support.UsernamePasswordToken.basicAuthHeaderValue;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

/**
 * Integration tests that test a transport client with security being loaded that connect to an external cluster
 */
public class SecurityTransportClientIT extends ESIntegTestCase {
    static final String ADMIN_USER_PW = "test_user:changeme";
    static final String TRANSPORT_USER_PW = "transport:changeme";

    @Override
    protected Settings externalClusterClientSettings() {
        return Settings.builder()
                .put(Security.USER_SETTING.getKey(), ADMIN_USER_PW)
                .build();
    }

    @Override
    protected Collection<Class<? extends Plugin>> transportClientPlugins() {
        return Collections.singletonList(XPackPlugin.class);
    }

    public void testThatTransportClientWithoutAuthenticationDoesNotWork() throws Exception {
        try (TransportClient client = transportClient(Settings.EMPTY)) {
            boolean connected = awaitBusy(() -> {
                return client.connectedNodes().size() > 0;
            }, 5L, TimeUnit.SECONDS);

            assertThat(connected, is(false));
        }
    }

    public void testThatTransportClientAuthenticationWithTransportClientRole() throws Exception {
        Settings settings = Settings.builder()
                .put(Security.USER_SETTING.getKey(), TRANSPORT_USER_PW)
                .build();
        try (TransportClient client = transportClient(settings)) {
            boolean connected = awaitBusy(() -> {
                return client.connectedNodes().size() > 0;
            }, 5L, TimeUnit.SECONDS);

            assertThat(connected, is(true));

            // this checks that the transport client is really running in a limited state
            try {
                client.admin().cluster().prepareHealth().get();
                fail("the transport user should not be be able to get health!");
            } catch (ElasticsearchSecurityException e) {
                assertThat(e.toString(), containsString("unauthorized"));
            }
        }
    }

    public void testTransportClientWithAdminUser() throws Exception {
        final boolean useTransportUser = randomBoolean();
        Settings settings = Settings.builder()
                .put(Security.USER_SETTING.getKey(), useTransportUser ? TRANSPORT_USER_PW : ADMIN_USER_PW)
                .build();
        try (TransportClient client = transportClient(settings)) {
            boolean connected = awaitBusy(() -> {
                return client.connectedNodes().size() > 0;
            }, 5L, TimeUnit.SECONDS);

            assertThat(connected, is(true));

            // this checks that the transport client is really running in a limited state
            ClusterHealthResponse response;
            if (useTransportUser) {
                response = client.filterWithHeader(Collections.singletonMap("Authorization",
                        basicAuthHeaderValue("test_user", new SecuredString("changeme".toCharArray()))))
                        .admin().cluster().prepareHealth().get();
            } else {
                response = client.admin().cluster().prepareHealth().get();
            }

            assertThat(response.isTimedOut(), is(false));
        }
    }

    TransportClient transportClient(Settings extraSettings) {
        NodesInfoResponse nodeInfos = client().admin().cluster().prepareNodesInfo().get();
        List<NodeInfo> nodes = nodeInfos.getNodes();
        assertTrue(nodes.isEmpty() == false);
        TransportAddress publishAddress = randomFrom(nodes).getTransport().address().publishAddress();
        String clusterName = nodeInfos.getClusterName().value();

        Settings settings = Settings.builder()
                .put(extraSettings)
                .put("cluster.name", clusterName)
                .build();

        return new MockTransportClient(settings, XPackPlugin.class).addTransportAddress(publishAddress);
    }
}
