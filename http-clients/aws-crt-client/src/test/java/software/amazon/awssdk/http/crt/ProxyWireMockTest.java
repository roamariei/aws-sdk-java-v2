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


package software.amazon.awssdk.http.crt;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static java.util.Collections.emptyMap;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.net.URI;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscriber;
import software.amazon.awssdk.crt.CrtResource;
import software.amazon.awssdk.crt.io.EventLoopGroup;
import software.amazon.awssdk.crt.io.HostResolver;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.SdkHttpResponse;
import software.amazon.awssdk.http.async.AsyncExecuteRequest;
import software.amazon.awssdk.http.async.SdkAsyncHttpClient;
import software.amazon.awssdk.http.async.SdkAsyncHttpResponseHandler;

/**
 * Tests for HTTP proxy functionality in the CRT client.
 */
public class ProxyWireMockTest {
    private SdkAsyncHttpClient client;

    private ProxyConfiguration proxyCfg;

    private WireMockServer mockProxy = new WireMockServer(new WireMockConfiguration()
            .dynamicPort()
            .dynamicHttpsPort()
            .enableBrowserProxying(true)); // make the mock proxy actually forward (to the mock server for our test)

    private WireMockServer mockServer = new WireMockServer(new WireMockConfiguration()
            .dynamicPort()
            .dynamicHttpsPort());


    @BeforeEach
    public void setup() {
        mockProxy.start();
        mockServer.start();

        mockServer.stubFor(get(urlMatching(".*")).willReturn(aResponse().withStatus(200).withBody("hello")));


    }

    @AfterEach
    public void teardown() {
        mockServer.stop();
        mockProxy.stop();
        if(client != null){
            client.close();
        }
        EventLoopGroup.closeStaticDefault();
        HostResolver.closeStaticDefault();
    }

    /*
     * Note the contrast between this test and the netty connect test.  The CRT proxy implementation does not
     * do a CONNECT call for requests using http, so by configuring the proxy mock to forward and the server mock
     * to return success, we can actually create an end-to-end test.
     *
     * We have an outstanding request to change this behavior to match https (use a CONNECT call).  Once that
     * change happens, this test will break and need to be updated to be more like the netty one.
     */
    @Test
    public void proxyConfigured_httpGet() throws Throwable {

        proxyCfg = ProxyConfiguration.builder()
                                     .host("localhost")
                                     .port(mockProxy.port())
                                     .build();

        client = AwsCrtAsyncHttpClient.builder()
                                      .proxyConfiguration(proxyCfg)
                                      .build();

        CompletableFuture<Boolean> streamReceived = new CompletableFuture<>();
        AtomicReference<SdkHttpResponse> response = new AtomicReference<>(null);
        AtomicReference<Throwable> error = new AtomicReference<>(null);

        Subscriber<ByteBuffer> subscriber = CrtHttpClientTestUtils.createDummySubscriber();

        SdkAsyncHttpResponseHandler handler =  CrtHttpClientTestUtils.createTestResponseHandler(response, streamReceived, error, subscriber);

        URI uri = URI.create("http://localhost:" + mockServer.port());
        SdkHttpRequest request = CrtHttpClientTestUtils.createRequest(uri, "/server/test", null, SdkHttpMethod.GET, emptyMap());

        CompletableFuture future = client.execute(AsyncExecuteRequest.builder()
                .request(request)
                .responseHandler(handler)
                .requestContentPublisher(new EmptyPublisher())
                .build());
        future.get(60, TimeUnit.SECONDS);
        assertThat(error.get()).isNull();
        assertThat(streamReceived.get(60, TimeUnit.SECONDS)).isTrue();
        assertThat(response.get().statusCode()).isEqualTo(200);
    }

    @Test
    public void proxyConfiguredAndSkippedUsingNonProxy_httpGet() throws Throwable {
        SecureRandom secureRandom = new SecureRandom();
        int randomPort = 0;
        // Generate a random port to test if the proxy host is not routed to when nonProxyHosts is specified.
        // If the nonProxyHosts had not matched, a connection exception would have occurred.
        do {
            randomPort = secureRandom.nextInt(65535);
        } while (randomPort == mockProxy.port());

        proxyCfg = ProxyConfiguration.builder()
                                     .host("localhost")
                                     .port(randomPort)
                                     .nonProxyHosts(Stream.of("localhost").collect(Collectors.toSet()))
                                     .build();

        client = AwsCrtAsyncHttpClient.builder()
                                      .proxyConfiguration(proxyCfg)
                                      .build();

        CompletableFuture<Boolean> streamReceived = new CompletableFuture<>();
        AtomicReference<SdkHttpResponse> response = new AtomicReference<>(null);
        AtomicReference<Throwable> error = new AtomicReference<>(null);

        Subscriber<ByteBuffer> subscriber = CrtHttpClientTestUtils.createDummySubscriber();

        SdkAsyncHttpResponseHandler handler = CrtHttpClientTestUtils.createTestResponseHandler(response, streamReceived, error,
                                                                                               subscriber);

        URI uri = URI.create("http://localhost:" + mockServer.port());
        SdkHttpRequest request = CrtHttpClientTestUtils.createRequest(uri, "/server/test", null, SdkHttpMethod.GET, emptyMap());

        CompletableFuture future = client.execute(AsyncExecuteRequest.builder()
                                                                     .request(request)
                                                                     .responseHandler(handler)
                                                                     .requestContentPublisher(new EmptyPublisher())
                                                                     .build());
        future.get(60, TimeUnit.SECONDS);
        assertThat(error.get()).isNull();
        assertThat(streamReceived.get(60, TimeUnit.SECONDS)).isTrue();
        assertThat(response.get().statusCode()).isEqualTo(200);
    }

}
