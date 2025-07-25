/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.awssdk.http.apache5;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.testutils.EnvironmentVariableHelper;

public class ProxyConfigurationTest {

    private static final EnvironmentVariableHelper ENVIRONMENT_VARIABLE_HELPER = new EnvironmentVariableHelper();

    @BeforeEach
    public void setup() {
        clearProxyProperties();
        ENVIRONMENT_VARIABLE_HELPER.reset();
    }

    @AfterAll
    public static void cleanup() {
        clearProxyProperties();
        ENVIRONMENT_VARIABLE_HELPER.reset();
    }

    @Test
    void testEndpointValues_Http_SystemPropertyEnabled() {
        String host = "foo.com";
        int port = 7777;
        System.setProperty("http.proxyHost", host);
        System.setProperty("http.proxyPort", Integer.toString(port));
        ENVIRONMENT_VARIABLE_HELPER.set("http_proxy", "http://UserOne:passwordSecret@bar.com:555/");
        ProxyConfiguration config = ProxyConfiguration.builder().useSystemPropertyValues(true).build();

        assertThat(config.host()).isEqualTo(host);
        assertThat(config.port()).isEqualTo(port);
        assertThat(config.scheme()).isEqualTo("http");

    }

    @Test
    void testEndpointValues_Http_EnvironmentVariableEnabled() {
        String host = "bar.com";
        int port = 7777;
        System.setProperty("http.proxyHost", "foo.com");
        System.setProperty("http.proxyPort", Integer.toString(8888));

        ENVIRONMENT_VARIABLE_HELPER.set("http_proxy", String.format("http://%s:%d/", host, port));

        ProxyConfiguration config =
            ProxyConfiguration.builder().useSystemPropertyValues(false).useEnvironmentVariableValues(true).build();

        assertThat(config.host()).isEqualTo(host);
        assertThat(config.port()).isEqualTo(port);
        assertThat(config.scheme()).isEqualTo("http");
    }

    @Test
    void testEndpointValues_Https_SystemPropertyEnabled() {
        String host = "foo.com";
        int port = 7777;
        System.setProperty("https.proxyHost", host);
        System.setProperty("https.proxyPort", Integer.toString(port));

        ProxyConfiguration config = ProxyConfiguration.builder()
                                                      .endpoint(URI.create("https://foo.com:7777"))
                                                      .useSystemPropertyValues(true).build();

        assertThat(config.host()).isEqualTo(host);
        assertThat(config.port()).isEqualTo(port);
        assertThat(config.scheme()).isEqualTo("https");
    }


    @Test
    void testEndpointValues_Https_EnvironmentVariableEnabled() {
        String host = "bar.com";
        int port = 7777;
        System.setProperty("https.proxyHost", "foo.com");
        System.setProperty("https.proxyPort", Integer.toString(8888));

        ENVIRONMENT_VARIABLE_HELPER.set("http_proxy", String.format("http://%s:%d/", "foo.com", 8888));
        ENVIRONMENT_VARIABLE_HELPER.set("https_proxy", String.format("http://%s:%d/", host, port));

        ProxyConfiguration config =
            ProxyConfiguration.builder()
                              .scheme("https")
                              .useSystemPropertyValues(false)
                              .useEnvironmentVariableValues(true)
                              .build();

        assertThat(config.host()).isEqualTo(host);
        assertThat(config.port()).isEqualTo(port);
        assertThat(config.scheme()).isEqualTo("https");
    }


    @Test
    void testEndpointValues_SystemPropertyDisabled() {
        ProxyConfiguration config = ProxyConfiguration.builder()
                                                      .endpoint(URI.create("http://localhost:1234"))
                                                      .useSystemPropertyValues(Boolean.FALSE)
                                                      .build();

        assertThat(config.host()).isEqualTo("localhost");
        assertThat(config.port()).isEqualTo(1234);
        assertThat(config.scheme()).isEqualTo("http");
    }

    @Test
    void testProxyConfigurationWithSystemPropertyDisabled() throws Exception {
        Set<String> nonProxyHosts = new HashSet<>();
        nonProxyHosts.add("foo.com");

        // system property should not be used
        System.setProperty("http.proxyHost", "foo.com");
        System.setProperty("http.proxyPort", "5555");
        System.setProperty("http.nonProxyHosts", "bar.com");
        System.setProperty("http.proxyUser", "user");

        ProxyConfiguration config = ProxyConfiguration.builder()
                                                      .endpoint(URI.create("http://localhost:1234"))
                                                      .nonProxyHosts(nonProxyHosts)
                                                      .useSystemPropertyValues(Boolean.FALSE)
                                                      .build();

        assertThat(config.host()).isEqualTo("localhost");
        assertThat(config.port()).isEqualTo(1234);
        assertThat(config.nonProxyHosts()).isEqualTo(nonProxyHosts);
        assertThat(config.username()).isNull();
    }

    @Test
    void testProxyConfigurationWithSystemPropertyEnabled_Http() throws Exception {
        Set<String> nonProxyHosts = new HashSet<>();
        nonProxyHosts.add("foo.com");

        // system property should not be used
        System.setProperty("http.proxyHost", "foo.com");
        System.setProperty("http.proxyPort", "5555");
        System.setProperty("http.nonProxyHosts", "bar.com");
        System.setProperty("http.proxyUser", "user");

        ProxyConfiguration config = ProxyConfiguration.builder()
                                                      .nonProxyHosts(nonProxyHosts)
                                                      .build();

        assertThat(config.nonProxyHosts()).isEqualTo(nonProxyHosts);
        assertThat(config.host()).isEqualTo("foo.com");
        assertThat(config.username()).isEqualTo("user");
    }

    @Test
    void testProxyConfigurationWithSystemPropertyEnabled_Https() throws Exception {
        Set<String> nonProxyHosts = new HashSet<>();
        nonProxyHosts.add("foo.com");

        // system property should not be used
        System.setProperty("https.proxyHost", "foo.com");
        System.setProperty("https.proxyPort", "5555");
        System.setProperty("http.nonProxyHosts", "bar.com");
        System.setProperty("https.proxyUser", "user");

        ProxyConfiguration config = ProxyConfiguration.builder()
                                                      .endpoint(URI.create("https://foo.com:1234"))
                                                      .nonProxyHosts(nonProxyHosts)
                                                      .build();

        assertThat(config.nonProxyHosts()).isEqualTo(nonProxyHosts);
        assertThat(config.host()).isEqualTo("foo.com");
        assertThat(config.username()).isEqualTo("user");
    }

    @Test
    void testProxyConfigurationWithoutNonProxyHosts_toBuilder_shouldNotThrowNPE() {
        ProxyConfiguration proxyConfiguration =
            ProxyConfiguration.builder()
                              .endpoint(URI.create("http://localhost:4321"))
                              .username("username")
                              .password("password")
                              .build();

        assertThat(proxyConfiguration.toBuilder()).isNotNull();
    }

    private static void clearProxyProperties() {
        System.clearProperty("http.proxyHost");
        System.clearProperty("http.proxyPort");
        System.clearProperty("http.nonProxyHosts");
        System.clearProperty("http.proxyUser");
        System.clearProperty("http.proxyPassword");

        System.clearProperty("https.proxyHost");
        System.clearProperty("https.proxyPort");
        System.clearProperty("https.proxyUser");
        System.clearProperty("https.proxyPassword");
    }
}
