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

package software.amazon.awssdk.awscore.internal;

import static software.amazon.awssdk.auth.signer.internal.util.SignerMethodResolver.resolveSigningMethodUsed;
import static software.amazon.awssdk.core.client.config.SdkClientOption.RETRY_POLICY;
import static software.amazon.awssdk.core.client.config.SdkClientOption.RETRY_STRATEGY;
import static software.amazon.awssdk.core.interceptor.SdkExecutionAttribute.RESOLVED_CHECKSUM_SPECS;
import static software.amazon.awssdk.core.internal.useragent.BusinessMetricsUtils.resolveRetryMode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import software.amazon.awssdk.annotations.SdkInternalApi;
import software.amazon.awssdk.auth.signer.AwsSignerExecutionAttribute;
import software.amazon.awssdk.awscore.AwsExecutionAttribute;
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration;
import software.amazon.awssdk.awscore.client.config.AwsClientOption;
import software.amazon.awssdk.awscore.internal.authcontext.AuthorizationStrategy;
import software.amazon.awssdk.awscore.internal.authcontext.AuthorizationStrategyFactory;
import software.amazon.awssdk.awscore.util.SignerOverrideUtils;
import software.amazon.awssdk.core.HttpChecksumConstant;
import software.amazon.awssdk.core.RequestOverrideConfiguration;
import software.amazon.awssdk.core.SdkRequest;
import software.amazon.awssdk.core.SdkResponse;
import software.amazon.awssdk.core.SelectedAuthScheme;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.core.client.config.SdkAdvancedClientOption;
import software.amazon.awssdk.core.client.config.SdkClientConfiguration;
import software.amazon.awssdk.core.client.config.SdkClientOption;
import software.amazon.awssdk.core.client.handler.ClientExecutionParams;
import software.amazon.awssdk.core.http.ExecutionContext;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptorChain;
import software.amazon.awssdk.core.interceptor.InterceptorContext;
import software.amazon.awssdk.core.interceptor.SdkExecutionAttribute;
import software.amazon.awssdk.core.interceptor.SdkInternalExecutionAttribute;
import software.amazon.awssdk.core.internal.InternalCoreExecutionAttribute;
import software.amazon.awssdk.core.internal.util.HttpChecksumResolver;
import software.amazon.awssdk.core.signer.Signer;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.core.useragent.AdditionalMetadata;
import software.amazon.awssdk.core.useragent.BusinessMetricCollection;
import software.amazon.awssdk.endpoints.EndpointProvider;
import software.amazon.awssdk.http.ContentStreamProvider;
import software.amazon.awssdk.http.auth.scheme.NoAuthAuthScheme;
import software.amazon.awssdk.http.auth.spi.scheme.AuthScheme;
import software.amazon.awssdk.http.auth.spi.scheme.AuthSchemeProvider;
import software.amazon.awssdk.identity.spi.IdentityProviders;
import software.amazon.awssdk.metrics.MetricCollector;

@SdkInternalApi
public final class AwsExecutionContextBuilder {

    private AwsExecutionContextBuilder() {

    }

    /**
     * Used by both sync and async clients to create the execution context, and run initial interceptors.
     */
    public static <InputT extends SdkRequest, OutputT extends SdkResponse> ExecutionContext
        invokeInterceptorsAndCreateExecutionContext(ClientExecutionParams<InputT, OutputT> executionParams,
                                                SdkClientConfiguration clientConfig) {
        // Note: This is currently copied to DefaultS3Presigner and other presigners.
        // Don't edit this without considering those

        SdkRequest originalRequest = executionParams.getInput();
        MetricCollector metricCollector = resolveMetricCollector(executionParams);

        ExecutionAttributes executionAttributes = mergeExecutionAttributeOverrides(
            executionParams.executionAttributes(),
            clientConfig.option(SdkClientOption.EXECUTION_ATTRIBUTES),
            originalRequest.overrideConfiguration().map(c -> c.executionAttributes()).orElse(null));

        executionAttributes.putAttributeIfAbsent(SdkExecutionAttribute.API_CALL_METRIC_COLLECTOR, metricCollector);

        executionAttributes
            .putAttribute(InternalCoreExecutionAttribute.EXECUTION_ATTEMPT, 1)
            .putAttribute(SdkExecutionAttribute.SERVICE_CONFIG,
                          clientConfig.option(SdkClientOption.SERVICE_CONFIGURATION))
            .putAttribute(AwsSignerExecutionAttribute.SERVICE_SIGNING_NAME,
                          clientConfig.option(AwsClientOption.SERVICE_SIGNING_NAME))
            .putAttribute(AwsExecutionAttribute.AWS_REGION, clientConfig.option(AwsClientOption.AWS_REGION))
            .putAttribute(AwsExecutionAttribute.ENDPOINT_PREFIX, clientConfig.option(AwsClientOption.ENDPOINT_PREFIX))
            .putAttribute(AwsSignerExecutionAttribute.SIGNING_REGION, clientConfig.option(AwsClientOption.SIGNING_REGION))
            .putAttribute(SdkInternalExecutionAttribute.IS_FULL_DUPLEX, executionParams.isFullDuplex())
            .putAttribute(SdkInternalExecutionAttribute.HAS_INITIAL_REQUEST_EVENT, executionParams.hasInitialRequestEvent())
            .putAttribute(SdkExecutionAttribute.CLIENT_TYPE, clientConfig.option(SdkClientOption.CLIENT_TYPE))
            .putAttribute(SdkExecutionAttribute.SERVICE_NAME, clientConfig.option(SdkClientOption.SERVICE_NAME))
            .putAttribute(SdkInternalExecutionAttribute.PROTOCOL_METADATA, executionParams.getProtocolMetadata())
            .putAttribute(SdkExecutionAttribute.PROFILE_FILE, clientConfig.option(SdkClientOption.PROFILE_FILE_SUPPLIER) != null ?
                                                              clientConfig.option(SdkClientOption.PROFILE_FILE_SUPPLIER).get() :
                                                              null)
            .putAttribute(SdkExecutionAttribute.PROFILE_FILE_SUPPLIER, clientConfig.option(SdkClientOption.PROFILE_FILE_SUPPLIER))
            .putAttribute(SdkExecutionAttribute.PROFILE_NAME, clientConfig.option(SdkClientOption.PROFILE_NAME))
            .putAttribute(AwsExecutionAttribute.DUALSTACK_ENDPOINT_ENABLED,
                          clientConfig.option(AwsClientOption.DUALSTACK_ENDPOINT_ENABLED))
            .putAttribute(AwsExecutionAttribute.FIPS_ENDPOINT_ENABLED,
                          clientConfig.option(AwsClientOption.FIPS_ENDPOINT_ENABLED))
            .putAttribute(SdkExecutionAttribute.OPERATION_NAME, executionParams.getOperationName())
            .putAttribute(SdkInternalExecutionAttribute.CLIENT_ENDPOINT_PROVIDER,
                          clientConfig.option(SdkClientOption.CLIENT_ENDPOINT_PROVIDER))
            .putAttribute(SdkInternalExecutionAttribute.ENDPOINT_PROVIDER,
                          resolveEndpointProvider(originalRequest, clientConfig))
            .putAttribute(SdkInternalExecutionAttribute.CLIENT_CONTEXT_PARAMS,
                          clientConfig.option(SdkClientOption.CLIENT_CONTEXT_PARAMS))
            .putAttribute(SdkInternalExecutionAttribute.DISABLE_HOST_PREFIX_INJECTION,
                          clientConfig.option(SdkAdvancedClientOption.DISABLE_HOST_PREFIX_INJECTION))
            .putAttribute(SdkInternalExecutionAttribute.SDK_CLIENT, clientConfig.option(SdkClientOption.SDK_CLIENT))
            .putAttribute(SdkExecutionAttribute.SIGNER_OVERRIDDEN, clientConfig.option(SdkClientOption.SIGNER_OVERRIDDEN))
            .putAttribute(AwsExecutionAttribute.USE_GLOBAL_ENDPOINT,
                          clientConfig.option(AwsClientOption.USE_GLOBAL_ENDPOINT))
            .putAttribute(AwsExecutionAttribute.AWS_AUTH_ACCOUNT_ID_ENDPOINT_MODE,
                          clientConfig.option(AwsClientOption.ACCOUNT_ID_ENDPOINT_MODE))
            .putAttribute(RESOLVED_CHECKSUM_SPECS, HttpChecksumResolver.resolveChecksumSpecs(executionAttributes))
            .putAttribute(SdkInternalExecutionAttribute.REQUEST_CHECKSUM_CALCULATION,
                          clientConfig.option(SdkClientOption.REQUEST_CHECKSUM_CALCULATION))
            .putAttribute(SdkInternalExecutionAttribute.RESPONSE_CHECKSUM_VALIDATION,
                          clientConfig.option(SdkClientOption.RESPONSE_CHECKSUM_VALIDATION))
            .putAttribute(SdkInternalExecutionAttribute.BUSINESS_METRICS, resolveUserAgentBusinessMetrics(clientConfig))
            .putAttribute(AwsExecutionAttribute.AWS_SIGV4A_SIGNING_REGION_SET,
                          clientConfig.option(AwsClientOption.AWS_SIGV4A_SIGNING_REGION_SET));

        // Auth Scheme resolution related attributes
        putAuthSchemeResolutionAttributes(executionAttributes, clientConfig, originalRequest);

        ExecutionInterceptorChain executionInterceptorChain =
            new ExecutionInterceptorChain(clientConfig.option(SdkClientOption.EXECUTION_INTERCEPTORS));

        InterceptorContext interceptorContext = InterceptorContext.builder()
                                                                  .request(originalRequest)
                                                                  .asyncRequestBody(executionParams.getAsyncRequestBody())
                                                                  .requestBody(executionParams.getRequestBody())
                                                                  .build();
        interceptorContext = runInitialInterceptors(interceptorContext, executionAttributes, executionInterceptorChain);

        SdkRequest modifiedRequests = interceptorContext.request();
        Signer signer = null;
        if (loadOldSigner(executionAttributes, modifiedRequests)) {
            AuthorizationStrategyFactory authorizationStrategyFactory =
                new AuthorizationStrategyFactory(modifiedRequests, metricCollector, clientConfig);
            AuthorizationStrategy authorizationStrategy =
                authorizationStrategyFactory.strategyFor(executionParams.credentialType());
            authorizationStrategy.addCredentialsToExecutionAttributes(executionAttributes);
            signer = authorizationStrategy.resolveSigner();
        }

        executionAttributes.putAttribute(HttpChecksumConstant.SIGNING_METHOD,
                                         resolveSigningMethodUsed(
                                             signer, executionAttributes, executionAttributes.getOptionalAttribute(
                                                 AwsSignerExecutionAttribute.AWS_CREDENTIALS).orElse(null)));

        putStreamingInputOutputTypesMetadata(executionAttributes, executionParams);

        return ExecutionContext.builder()
                               .interceptorChain(executionInterceptorChain)
                               .interceptorContext(interceptorContext)
                               .executionAttributes(executionAttributes)
                               .signer(signer)
                               .metricCollector(metricCollector)
                               .build();
    }

    private static <InputT extends SdkRequest, OutputT extends SdkResponse> void putStreamingInputOutputTypesMetadata(
        ExecutionAttributes executionAttributes, ClientExecutionParams<InputT, OutputT> executionParams) {
        List<AdditionalMetadata> userAgentMetadata = new ArrayList<>();

        if (executionParams.getRequestBody() != null) {
            userAgentMetadata.add(
                AdditionalMetadata
                    .builder()
                    .name("rb")
                    .value(ContentStreamProvider.ProviderType.shortValueFromName(
                        executionParams.getRequestBody().contentStreamProvider().name())
                    )
                    .build());
        }

        if (executionParams.getAsyncRequestBody() != null) {
            userAgentMetadata.add(
                AdditionalMetadata
                    .builder()
                    .name("rb")
                    .value(AsyncRequestBody.BodyType.shortValueFromName(
                        executionParams.getAsyncRequestBody().body())
                    )
                    .build());
        }

        if (executionParams.getResponseTransformer() != null) {
            userAgentMetadata.add(
                AdditionalMetadata
                    .builder()
                    .name("rt")
                    .value(ResponseTransformer.TransformerType.shortValueFromName(
                        executionParams.getResponseTransformer().name())
                    )
                    .build());
        }

        if (executionParams.getAsyncResponseTransformer() != null) {
            userAgentMetadata.add(
                AdditionalMetadata
                    .builder()
                    .name("rt")
                    .value(AsyncResponseTransformer.TransformerType.shortValueFromName(
                        executionParams.getAsyncResponseTransformer().name())
                    )
                    .build());
        }

        executionAttributes.putAttribute(SdkInternalExecutionAttribute.USER_AGENT_METADATA, userAgentMetadata);
    }

    /**
     * We will load the old (non-SRA) signer if this client seems like an old version or the customer has provided a signer
     * override. We assume that if there's no auth schemes defined, we're on the old code path.
     * <p>
     * In addition, if authType=none, we don't need to use the old signer, even if overridden.
     */
    private static boolean loadOldSigner(ExecutionAttributes attributes, SdkRequest request) {
        Map<String, AuthScheme<?>> authSchemes = attributes.getAttribute(SdkInternalExecutionAttribute.AUTH_SCHEMES);
        if (authSchemes == null) {
            // pre SRA case.
            // We used to set IS_NONE_AUTH_TYPE_REQUEST = false when authType=none. Yes, false.
            return attributes.getOptionalAttribute(SdkInternalExecutionAttribute.IS_NONE_AUTH_TYPE_REQUEST).orElse(true);
        }

        // post SRA case.
        // By default, SRA uses new HttpSigner, so we shouldn't use old non-SRA Signer, unless the customer has provided a signer
        // override.
        // But, if the operation was modeled as authTpye=None, we don't want to use the provided overridden Signer either. In
        // post SRA, modeled authType=None would default to NoAuthAuthScheme.
        // Note, for authType=None operation, technically, customer could override the AuthSchemeProvider and select a different
        // AuthScheme (than NoAuthAuthScheme). In this case, we are choosing to use the customer's overridden Signer.
        SelectedAuthScheme<?> selectedAuthScheme = attributes.getAttribute(SdkInternalExecutionAttribute.SELECTED_AUTH_SCHEME);
        return SignerOverrideUtils.isSignerOverridden(request, attributes) &&
               selectedAuthScheme != null &&
               !NoAuthAuthScheme.SCHEME_ID.equals(selectedAuthScheme.authSchemeOption().schemeId());
    }

    private static void putAuthSchemeResolutionAttributes(ExecutionAttributes executionAttributes,
                                                          SdkClientConfiguration clientConfig,
                                                          SdkRequest originalRequest) {

        // TODO(sra-identity-and-auth): When request-level auth scheme provider is added, use the request-level auth scheme
        //  provider if the customer specified an override, otherwise fall back to the one on the client.
        AuthSchemeProvider authSchemeProvider = clientConfig.option(SdkClientOption.AUTH_SCHEME_PROVIDER);

        // Use auth schemes that the user specified at the request level with
        // preference over those on the client.
        // TODO(sra-identity-and-auth): The request level schemes should be "merged" with client level, with request preferred
        //  over client.
        Map<String, AuthScheme<?>> authSchemes = clientConfig.option(SdkClientOption.AUTH_SCHEMES);

        IdentityProviders identityProviders = resolveIdentityProviders(originalRequest, clientConfig);

        executionAttributes
            .putAttribute(SdkInternalExecutionAttribute.AUTH_SCHEME_RESOLVER, authSchemeProvider)
            .putAttribute(SdkInternalExecutionAttribute.AUTH_SCHEMES, authSchemes)
            .putAttribute(SdkInternalExecutionAttribute.IDENTITY_PROVIDERS, identityProviders);
    }

    private static IdentityProviders resolveIdentityProviders(SdkRequest originalRequest,
                                                              SdkClientConfiguration clientConfig) {
        IdentityProviders identityProviders =
            clientConfig.option(SdkClientOption.IDENTITY_PROVIDERS);

        // identityProviders can be null, for new core with old client. In this case, even if AwsRequestOverrideConfiguration
        // has credentialsIdentityProvider set (because it is in new core), it is ok to not setup IDENTITY_PROVIDERS, as old
        // client won't have AUTH_SCHEME_PROVIDER/AUTH_SCHEMES set either, which are also needed for SRA logic.
        if (identityProviders == null) {
            return null;
        }

        return originalRequest
            .overrideConfiguration()
            .filter(c -> c instanceof AwsRequestOverrideConfiguration)
            .map(c -> (AwsRequestOverrideConfiguration) c)
            .map(c -> {
                return identityProviders.copy(b -> {
                    c.credentialsIdentityProvider().ifPresent(b::putIdentityProvider);
                    c.tokenIdentityProvider().ifPresent(b::putIdentityProvider);
                });
            })
            .orElse(identityProviders);
    }

    /**
     * Finalize {@link SdkRequest} by running beforeExecution and modifyRequest interceptors.
     *
     * @param interceptorContext containing the immutable SdkRequest information the interceptor can act on
     * @param executionAttributes mutable container of attributes concerning the execution and request
     * @return the {@link InterceptorContext} returns a context with a new SdkRequest
     */
    public static InterceptorContext runInitialInterceptors(InterceptorContext interceptorContext,
                                                            ExecutionAttributes executionAttributes,
                                                            ExecutionInterceptorChain executionInterceptorChain) {
        executionInterceptorChain.beforeExecution(interceptorContext, executionAttributes);
        return executionInterceptorChain.modifyRequest(interceptorContext, executionAttributes);
    }


    private static <InputT extends SdkRequest, OutputT extends SdkResponse> ExecutionAttributes mergeExecutionAttributeOverrides(
        ExecutionAttributes executionAttributes,
        ExecutionAttributes clientOverrideExecutionAttributes,
        ExecutionAttributes requestOverrideExecutionAttributes) {


        executionAttributes.putAbsentAttributes(requestOverrideExecutionAttributes);
        executionAttributes.putAbsentAttributes(clientOverrideExecutionAttributes);

        return executionAttributes;
    }

    private static MetricCollector resolveMetricCollector(ClientExecutionParams<?, ?> params) {
        MetricCollector metricCollector = params.getMetricCollector();
        if (metricCollector == null) {
            metricCollector = MetricCollector.create("ApiCall");
        }
        return metricCollector;
    }

    /**
     * Resolves the endpoint provider, with the request override configuration taking precedence over the provided client
     * configuration.
     *
     * @return The endpoint provider that will be used by the SDK to resolve endpoints.
     */
    private static EndpointProvider resolveEndpointProvider(SdkRequest request,
                                                            SdkClientConfiguration clientConfig) {
        return request.overrideConfiguration()
                      .flatMap(RequestOverrideConfiguration::endpointProvider)
                      .orElse(clientConfig.option(SdkClientOption.ENDPOINT_PROVIDER));
    }

    private static BusinessMetricCollection resolveUserAgentBusinessMetrics(SdkClientConfiguration clientConfig) {
        BusinessMetricCollection businessMetrics = new BusinessMetricCollection();
        Optional<String> retryModeMetric = resolveRetryMode(clientConfig.option(RETRY_POLICY),
                                                            clientConfig.option(RETRY_STRATEGY));
        retryModeMetric.ifPresent(businessMetrics::addMetric);
        return businessMetrics;
    }
}
