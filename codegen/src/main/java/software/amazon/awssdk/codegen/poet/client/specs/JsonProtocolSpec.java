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

package software.amazon.awssdk.codegen.poet.client.specs;

import static software.amazon.awssdk.codegen.model.intermediate.Protocol.AWS_JSON;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeVariableName;
import com.squareup.javapoet.WildcardTypeName;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import javax.lang.model.element.Modifier;
import software.amazon.awssdk.awscore.eventstream.EventStreamAsyncResponseTransformer;
import software.amazon.awssdk.awscore.eventstream.EventStreamTaggedUnionPojoSupplier;
import software.amazon.awssdk.awscore.eventstream.RestEventStreamAsyncResponseTransformer;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.codegen.model.config.customization.MetadataConfig;
import software.amazon.awssdk.codegen.model.intermediate.IntermediateModel;
import software.amazon.awssdk.codegen.model.intermediate.Metadata;
import software.amazon.awssdk.codegen.model.intermediate.OperationModel;
import software.amazon.awssdk.codegen.model.intermediate.Protocol;
import software.amazon.awssdk.codegen.model.intermediate.ShapeModel;
import software.amazon.awssdk.codegen.model.intermediate.ShapeType;
import software.amazon.awssdk.codegen.poet.PoetExtension;
import software.amazon.awssdk.codegen.poet.auth.scheme.AuthSchemeSpecUtils;
import software.amazon.awssdk.codegen.poet.client.traits.HttpChecksumRequiredTrait;
import software.amazon.awssdk.codegen.poet.client.traits.HttpChecksumTrait;
import software.amazon.awssdk.codegen.poet.client.traits.NoneAuthTypeRequestTrait;
import software.amazon.awssdk.codegen.poet.client.traits.RequestCompressionTrait;
import software.amazon.awssdk.codegen.poet.eventstream.EventStreamUtils;
import software.amazon.awssdk.codegen.poet.model.EventStreamSpecHelper;
import software.amazon.awssdk.core.SdkPojoBuilder;
import software.amazon.awssdk.core.SdkResponse;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.core.client.handler.AttachHttpMetadataResponseHandler;
import software.amazon.awssdk.core.client.handler.ClientExecutionParams;
import software.amazon.awssdk.core.http.HttpResponseHandler;
import software.amazon.awssdk.core.protocol.VoidSdkResponse;
import software.amazon.awssdk.protocols.cbor.AwsCborProtocolFactory;
import software.amazon.awssdk.protocols.core.ExceptionMetadata;
import software.amazon.awssdk.protocols.json.AwsJsonProtocol;
import software.amazon.awssdk.protocols.json.AwsJsonProtocolFactory;
import software.amazon.awssdk.protocols.json.BaseAwsJsonProtocolFactory;
import software.amazon.awssdk.protocols.json.JsonOperationMetadata;
import software.amazon.awssdk.protocols.rpcv2.SmithyRpcV2CborProtocolFactory;
import software.amazon.awssdk.utils.CompletableFutureUtils;

public class JsonProtocolSpec implements ProtocolSpec {

    private final PoetExtension poetExtensions;
    private final IntermediateModel model;
    private final boolean useSraAuth;

    public JsonProtocolSpec(PoetExtension poetExtensions, IntermediateModel model) {
        this.poetExtensions = poetExtensions;
        this.model = model;
        this.useSraAuth = new AuthSchemeSpecUtils(model).useSraAuth();
    }

    @Override
    public FieldSpec protocolFactory(IntermediateModel model) {
        return FieldSpec.builder(protocolFactoryClass(), "protocolFactory")
                        .addModifiers(Modifier.PRIVATE, Modifier.FINAL).build();
    }

    @Override
    public MethodSpec initProtocolFactory(IntermediateModel model) {
        ClassName baseException = baseExceptionClassName(model);
        Metadata metadata = model.getMetadata();
        ParameterizedTypeName upperBound = ParameterizedTypeName.get(ClassName.get(BaseAwsJsonProtocolFactory.Builder.class),
                                                                     TypeVariableName.get("T"));
        TypeVariableName typeVariableName = TypeVariableName.get("T", upperBound);

        MethodSpec.Builder methodSpec =
            MethodSpec.methodBuilder("init")
                      .addTypeVariable(typeVariableName)
                      .addParameter(typeVariableName, "builder")
                      .returns(typeVariableName)
                      .addModifiers(Modifier.PRIVATE)
                      .addCode("return builder\n")
                      .addCode(".clientConfiguration(clientConfiguration)\n")
                      .addCode(".defaultServiceExceptionSupplier($T::builder)\n", baseException)
                      .addCode(".protocol($T.$L)\n", AwsJsonProtocol.class, protocolEnumName(metadata.getProtocol()));
        if (metadata.getJsonVersion() != null) {
            methodSpec.addCode(".protocolVersion($S)\n", metadata.getJsonVersion());
        }
        methodSpec.addCode("$L", customErrorCodeFieldName());
        String contentType = Optional.ofNullable(model.getCustomizationConfig().getCustomServiceMetadata())
                .map(MetadataConfig::getContentType)
                .orElse(metadata.getContentType());

        if (contentType != null) {
            methodSpec.addCode(".contentType($S)", contentType);
        }

        if (metadata.getAwsQueryCompatible() != null) {
            methodSpec.addCode("$L", hasAwsQueryCompatible());
        }

        methodSpec.addCode(";");

        return methodSpec.build();
    }

    private CodeBlock customErrorCodeFieldName() {
        return model.getCustomizationConfig().getCustomErrorCodeFieldName() == null ?
               CodeBlock.builder().build() :
               CodeBlock.of(".customErrorCodeFieldName($S)", model.getCustomizationConfig().getCustomErrorCodeFieldName());
    }

    private CodeBlock hasAwsQueryCompatible() {
        return CodeBlock.of(".hasAwsQueryCompatible($L)", model.getMetadata().getAwsQueryCompatible() != null);
    }

    private Class<?> protocolFactoryClass() {
        if (model.getMetadata().isCborProtocol()) {
            return AwsCborProtocolFactory.class;
        } else if (model.getMetadata().isRpcV2CborProtocol()) {
            return SmithyRpcV2CborProtocolFactory.class;
        } else {
            return AwsJsonProtocolFactory.class;
        }
    }

    @Override
    public CodeBlock responseHandler(IntermediateModel model, OperationModel opModel) {
        TypeName pojoResponseType = getPojoResponseType(opModel, poetExtensions);

        String protocolFactory = protocolFactoryLiteral(model, opModel);
        CodeBlock.Builder builder =
            CodeBlock.builder()
                     .add("$T operationMetadata = $T.builder()\n", JsonOperationMetadata.class, JsonOperationMetadata.class)
                     .add(".hasStreamingSuccessResponse($L)\n", opModel.hasStreamingOutput())
                     .add(".isPayloadJson($L)\n", !opModel.getHasBlobMemberAsPayload() && !opModel.getHasStringMemberAsPayload())
                     .add(".build();");

        if (opModel.hasEventStreamOutput()) {
            responseHandlersForEventStreaming(opModel, pojoResponseType, protocolFactory, builder);
        } else {
            builder.add("\n\n$T<$T> responseHandler = $L.createResponseHandler(operationMetadata, $T::builder);",
                        HttpResponseHandler.class,
                        pojoResponseType,
                        protocolFactory,
                        pojoResponseType);
        }
        return builder.build();
    }

    @Override
    public Optional<CodeBlock> errorResponseHandler(OperationModel opModel) {
        String protocolFactory = protocolFactoryLiteral(model, opModel);

        CodeBlock.Builder builder = CodeBlock.builder();
        ParameterizedTypeName metadataMapperType = ParameterizedTypeName.get(
            ClassName.get(Function.class),
            ClassName.get(String.class),
            ParameterizedTypeName.get(Optional.class, ExceptionMetadata.class));

        builder.add("\n$T exceptionMetadataMapper = errorCode -> {\n", metadataMapperType);
        builder.add("if (errorCode == null) {\n");
        builder.add("return $T.empty();\n", Optional.class);
        builder.add("}\n");
        builder.add("switch (errorCode) {\n");
        model.getShapes().values().stream()
             .filter(shape -> shape.getShapeType() == ShapeType.Exception)
             .forEach(exceptionShape -> {
                 String exceptionName = exceptionShape.getShapeName();
                 String errorCode = exceptionShape.getErrorCode();

                 builder.add("case $S:\n", errorCode);
                 builder.add("return $T.of($T.builder()\n", Optional.class, ExceptionMetadata.class)
                        .add(".errorCode($S)\n", errorCode);
                 builder.add(populateHttpStatusCode(exceptionShape, model));
                 builder.add(".exceptionBuilderSupplier($T::builder)\n",
                             poetExtensions.getModelClassFromShape(exceptionShape))
                        .add(".build());\n");
             });

        builder.add("default: return $T.empty();\n", Optional.class);
        builder.add("}\n");
        builder.add("};\n");

        builder.add("$T<$T> errorResponseHandler = createErrorResponseHandler($L, operationMetadata, exceptionMetadataMapper);",
                    HttpResponseHandler.class, AwsServiceException.class, protocolFactory);

        return Optional.of(builder.build());
    }

    @Override
    public CodeBlock executionHandler(OperationModel opModel) {
        TypeName responseType = getPojoResponseType(opModel, poetExtensions);
        ClassName requestType = poetExtensions.getModelClass(opModel.getInput().getVariableType());
        ClassName marshaller = poetExtensions.getRequestTransformClass(opModel.getInputShape().getShapeName() + "Marshaller");

        CodeBlock.Builder codeBlock =
            CodeBlock.builder()
                     .add("\n\nreturn clientHandler.execute(new $T<$T, $T>()\n",
                          ClientExecutionParams.class, requestType, responseType)
                     .add(".withOperationName(\"$N\")\n", opModel.getOperationName())
                     .add(".withProtocolMetadata(protocolMetadata)\n")
                     .add(".withResponseHandler(responseHandler)\n")
                     .add(".withErrorResponseHandler(errorResponseHandler)\n")
                     .add(hostPrefixExpression(opModel))
                     .add(discoveredEndpoint(opModel))
                     .add(credentialType(opModel, model))
                     .add(".withRequestConfiguration(clientConfiguration)")
                     .add(".withInput($L)\n", opModel.getInput().getVariableName())
                     .add(".withMetricCollector(apiCallMetricCollector)")
                     .add(HttpChecksumRequiredTrait.putHttpChecksumAttribute(opModel))
                     .add(HttpChecksumTrait.create(opModel));

        if (!useSraAuth) {
            codeBlock.add(NoneAuthTypeRequestTrait.create(opModel));
        }

        codeBlock.add(RequestCompressionTrait.create(opModel, model));

        if (opModel.hasStreamingOutput()) {
            codeBlock.add(".withResponseTransformer(responseTransformer)");
        }

        if (opModel.hasStreamingInput()) {
            codeBlock.add(".withRequestBody(requestBody)")
                     .add(".withMarshaller($L)", syncStreamingMarshaller(model, opModel, marshaller));
        } else {
            codeBlock.add(".withMarshaller(new $T(protocolFactory))", marshaller);
        }

        return codeBlock.add("$L);", opModel.hasStreamingOutput() ? ", responseTransformer" : "")
                        .build();
    }

    @Override
    public CodeBlock asyncExecutionHandler(IntermediateModel intermediateModel, OperationModel opModel) {
        boolean isRestJson = isRestJson(intermediateModel);
        TypeName pojoResponseType = getPojoResponseType(opModel, poetExtensions);
        ClassName requestType = poetExtensions.getModelClass(opModel.getInput().getVariableType());
        ClassName marshaller = poetExtensions.getRequestTransformClass(opModel.getInputShape().getShapeName() + "Marshaller");

        String asyncRequestBody = opModel.hasStreamingInput() ? ".withAsyncRequestBody(requestBody)"
                                                              : "";
        CodeBlock.Builder builder = CodeBlock.builder();
        if (opModel.hasEventStreamOutput()) {
            ShapeModel shapeModel = EventStreamUtils.getEventStreamInResponse(opModel.getOutputShape());
            ClassName eventStreamBaseClass = poetExtensions.getModelClassFromShape(shapeModel);
            ParameterizedTypeName transformerType = ParameterizedTypeName.get(
                ClassName.get(EventStreamAsyncResponseTransformer.class), pojoResponseType, eventStreamBaseClass);

            builder.add("$1T<$2T> future = new $1T<>();", ClassName.get(CompletableFuture.class), ClassName.get(Void.class))
                   .add("$T asyncResponseTransformer = $T.<$T, $T>builder()\n",
                        transformerType, ClassName.get(EventStreamAsyncResponseTransformer.class), pojoResponseType,
                        eventStreamBaseClass)
                   .add(".eventStreamResponseHandler(asyncResponseHandler)\n")
                   .add(".eventResponseHandler(eventResponseHandler)\n")
                   .add(".initialResponseHandler(responseHandler)\n")
                   .add(".exceptionResponseHandler(errorEventResponseHandler)\n")
                   .add(".future(future)\n")
                   .add(".executor(executor)\n")
                   .add(".serviceName(serviceName())\n")
                   .add(".build();");

            if (isRestJson) {
                builder.add(restAsyncResponseTransformer(pojoResponseType, eventStreamBaseClass));
            }
        }

        boolean isStreaming = opModel.hasStreamingOutput() || opModel.hasEventStreamOutput();
        String protocolFactory = protocolFactoryLiteral(intermediateModel, opModel);
        TypeName responseType = opModel.hasEventStreamOutput() && !isRestJson ? ClassName.get(SdkResponse.class)
                                                                              : pojoResponseType;
        TypeName executeFutureValueType = executeFutureValueType(opModel, poetExtensions);

        builder.add("\n\n$T<$T> executeFuture = ", CompletableFuture.class, executeFutureValueType)
               .add(opModel.getEndpointDiscovery() != null ? "endpointFuture.thenCompose(cachedEndpoint -> " : "")
               .add("clientHandler.execute(new $T<$T, $T>()\n", ClientExecutionParams.class, requestType, responseType)
               .add(".withOperationName(\"$N\")\n", opModel.getOperationName())
               .add(".withProtocolMetadata(protocolMetadata)\n")
               .add(".withMarshaller($L)\n", asyncMarshaller(model, opModel, marshaller, protocolFactory))
               .add(asyncRequestBody(opModel))
               .add(fullDuplex(opModel))
               .add(hasInitialRequestEvent(opModel, isRestJson))
               .add(".withResponseHandler($L)\n", responseHandlerName(opModel, isRestJson))
               .add(".withErrorResponseHandler(errorResponseHandler)\n")
               .add(".withRequestConfiguration(clientConfiguration)")
               .add(".withMetricCollector(apiCallMetricCollector)\n")
               .add(hostPrefixExpression(opModel))
               .add(discoveredEndpoint(opModel))
               .add(credentialType(opModel, model))
               .add(asyncRequestBody)
               .add(HttpChecksumRequiredTrait.putHttpChecksumAttribute(opModel))
               .add(HttpChecksumTrait.create(opModel));

        if (!useSraAuth) {
            builder.add(NoneAuthTypeRequestTrait.create(opModel));
        }

        if (opModel.hasStreamingOutput()) {
            builder.add(".withAsyncResponseTransformer(asyncResponseTransformer)");
        }

        builder.add(RequestCompressionTrait.create(opModel, model))
               .add(".withInput($L)$L)",
                    opModel.getInput().getVariableName(), asyncResponseTransformerVariable(isStreaming, isRestJson, opModel))
               .add(opModel.getEndpointDiscovery() != null ? ");" : ";");

        if (opModel.hasStreamingOutput()) {
            builder.addStatement("$T<$T, ReturnT> finalAsyncResponseTransformer = asyncResponseTransformer",
                                 AsyncResponseTransformer.class,
                                 pojoResponseType);
        }
        String customerResponseHandler = opModel.hasEventStreamOutput() ?
                                         "asyncResponseHandler" : "finalAsyncResponseTransformer";
        String whenComplete = whenCompleteBody(opModel, customerResponseHandler);
        if (!whenComplete.isEmpty()) {
            String whenCompletedFutureName = "whenCompleted";
            builder.addStatement("$T<$T> $N = $N$L", CompletableFuture.class, executeFutureValueType,
                    whenCompletedFutureName, "executeFuture", whenComplete);
            builder.addStatement("executeFuture = $T.forwardExceptionTo($N, executeFuture)",
                    CompletableFutureUtils.class, whenCompletedFutureName);
        }
        if (opModel.hasEventStreamOutput()) {
            builder.addStatement("return $T.forwardExceptionTo(future, executeFuture)", CompletableFutureUtils.class);
        } else {
            builder.addStatement("return executeFuture");
        }
        return builder.build();
    }

    private String responseHandlerName(OperationModel opModel, boolean isRestJson) {
        return opModel.hasEventStreamOutput() && !isRestJson ? "voidResponseHandler"
                                                             : "responseHandler";
    }

    private CodeBlock fullDuplex(OperationModel opModel) {
        return opModel.hasEventStreamInput() && opModel.hasEventStreamOutput() ? CodeBlock.of(".withFullDuplex(true)")
                                                                               : CodeBlock.of("");
    }

    private CodeBlock hasInitialRequestEvent(OperationModel opModel, boolean isRestJson) {
        return opModel.hasEventStreamInput() && !isRestJson ? CodeBlock.of(".withInitialRequestEvent(true)")
                                                            : CodeBlock.of("");
    }

    private CodeBlock asyncRequestBody(OperationModel opModel) {
        return opModel.hasEventStreamInput() ? CodeBlock.of(".withAsyncRequestBody($T.fromPublisher(adapted))",
                                                            AsyncRequestBody.class)
                                             : CodeBlock.of("");
    }

    private String asyncResponseTransformerVariable(boolean isStreaming, boolean isRestJson, OperationModel opModel) {
        if (isStreaming) {
            if (opModel.hasEventStreamOutput() && isRestJson) {
                return ", restAsyncResponseTransformer";
            } else {
                return ", asyncResponseTransformer";
            }
        }
        return "";
    }

    /**
     * For Rest services, we need to use the {@link RestEventStreamAsyncResponseTransformer} instead of
     * {@link EventStreamAsyncResponseTransformer} class. This method has the code to create a restAsyncResponseTransformer
     * variable.
     *
     * @param pojoResponseType Type of operation response shape
     * @param eventStreamBaseClass Class name for the base class of all events in the operation
     */
    private CodeBlock restAsyncResponseTransformer(TypeName pojoResponseType, ClassName eventStreamBaseClass) {
        ParameterizedTypeName restTransformerType = ParameterizedTypeName.get(
            ClassName.get(RestEventStreamAsyncResponseTransformer.class), pojoResponseType, eventStreamBaseClass);
        return CodeBlock.builder()
                        .add("$T restAsyncResponseTransformer = $T.<$T, $T>builder()\n",
                             restTransformerType, ClassName.get(RestEventStreamAsyncResponseTransformer.class), pojoResponseType,
                             eventStreamBaseClass)
                        .add(".eventStreamAsyncResponseTransformer(asyncResponseTransformer)\n")
                        .add(".eventStreamResponseHandler(asyncResponseHandler)\n")
                        .add(".build();")
                        .build();
    }


    /**
     * For streaming operations we need to notify the response handler or response transformer on exception so
     * we add a .whenComplete to the future.
     *
     * @param operationModel Op model.
     * @param responseHandlerName Variable name of response handler customer passed in.
     * @return whenComplete to append to future.
     */
    private String whenCompleteBody(OperationModel operationModel, String responseHandlerName) {
        if (operationModel.hasEventStreamOutput()) {
            return eventStreamOutputWhenComplete(responseHandlerName);
        } else if (operationModel.hasStreamingOutput()) {
            return streamingOutputWhenComplete(responseHandlerName);
        } else {
            // Non streaming can just return the future as is
            return publishMetricsWhenComplete();
        }
    }

    /**
     * For event streaming our future notification is a bit complicated. We create a different future that is not tied
     * to the lifecycle of the wire request. Successful completion of the future is signalled in
     * {@link EventStreamAsyncResponseTransformer}. Failure is notified via the normal future (the one returned by the client
     * handler).
     *
     *
     * @param responseHandlerName Variable name of response handler customer passed in.
     * @return whenComplete to append to future.
     */
    private String eventStreamOutputWhenComplete(String responseHandlerName) {
        return String.format(".whenComplete((r, e) -> {%n"
                             + "     if (e != null) {%n"
                             + "         try {"
                             + "             %s.exceptionOccurred(e);%n"
                             + "         } finally {"
                             + "             future.completeExceptionally(e);"
                             + "         }"
                             + "     }"
                             + "%s"
                             + "})", responseHandlerName, publishMetrics());
    }


    @Override
    public Optional<MethodSpec> createErrorResponseHandler() {
        ClassName httpResponseHandler = ClassName.get(HttpResponseHandler.class);
        ClassName sdkBaseException = ClassName.get(AwsServiceException.class);
        TypeName responseHandlerOfException = ParameterizedTypeName.get(httpResponseHandler, sdkBaseException);
        ParameterizedTypeName mapperType = ParameterizedTypeName.get(ClassName.get(Function.class),
            ClassName.get(String.class), ParameterizedTypeName.get(Optional.class, ExceptionMetadata.class));

        return Optional.of(MethodSpec.methodBuilder("createErrorResponseHandler")
                                     .addParameter(BaseAwsJsonProtocolFactory.class, "protocolFactory")
                                     .addParameter(JsonOperationMetadata.class, "operationMetadata")
                                     .addParameter(mapperType, "exceptionMetadataMapper")
                                     .returns(responseHandlerOfException)
                                     .addModifiers(Modifier.PRIVATE)
                                     .addStatement("return protocolFactory.createErrorResponseHandler(operationMetadata, "
                                                   + "exceptionMetadataMapper)")
                                     .build());
    }

    private String protocolEnumName(software.amazon.awssdk.codegen.model.intermediate.Protocol protocol) {
        switch (protocol) {
            case CBOR:
            case AWS_JSON:
                return AWS_JSON.name();
            default:
                return protocol.name();
        }
    }

    private ClassName baseExceptionClassName(IntermediateModel model) {
        String exceptionPath = model.getSdkModeledExceptionBaseFqcn()
                                    .substring(0, model.getSdkModeledExceptionBaseFqcn().lastIndexOf('.'));

        return ClassName.get(exceptionPath, model.getSdkModeledExceptionBaseClassName());
    }

    /**
     * Add responseHandlers for event streaming operations
     */
    private void responseHandlersForEventStreaming(OperationModel opModel, TypeName pojoResponseType,
                                                   String protocolFactory, CodeBlock.Builder builder) {
        builder.add("\n\n$T<$T> responseHandler = new $T($L.createResponseHandler(operationMetadata, $T::builder));",
                    HttpResponseHandler.class,
                    pojoResponseType,
                    AttachHttpMetadataResponseHandler.class,
                    protocolFactory,
                    pojoResponseType);

        builder.add("\n\n$T<$T> voidResponseHandler = $L.createResponseHandler($T.builder()\n" +
                    "                                   .isPayloadJson(false)\n" +
                    "                                   .hasStreamingSuccessResponse(true)\n" +
                    "                                   .build(), $T::builder);",
                    HttpResponseHandler.class,
                    SdkResponse.class,
                    protocolFactory,
                    JsonOperationMetadata.class,
                    VoidSdkResponse.class);

        ShapeModel eventStream = EventStreamUtils.getEventStreamInResponse(opModel.getOutputShape());
        ClassName eventStreamBaseClass = poetExtensions.getModelClassFromShape(eventStream);
        builder
            .add("\n\n$T<$T> eventResponseHandler = $L.createResponseHandler($T.builder()\n" +
                 "                                   .isPayloadJson(true)\n" +
                 "                                   .hasStreamingSuccessResponse(false)\n" +
                 "                                   .build(), $T.builder()",
                 HttpResponseHandler.class,
                 WildcardTypeName.subtypeOf(eventStreamBaseClass),
                 protocolFactory,
                 JsonOperationMetadata.class,
                 ClassName.get(EventStreamTaggedUnionPojoSupplier.class));

        EventStreamSpecHelper eventStreamSpecHelper = new EventStreamSpecHelper(eventStream, model);
        EventStreamUtils.getEventMembers(eventStream)
                        .forEach(m -> {
                            String builderMethod = eventStreamSpecHelper.eventBuilderMethodName(m);
                            builder.add(".putSdkPojoSupplier($S, $T::$N)",
                                        m.getC2jName(), eventStreamBaseClass, builderMethod);
                        });
        builder.add(".defaultSdkPojoSupplier(() -> new $T($T.UNKNOWN))\n"
                    + ".build());\n", SdkPojoBuilder.class, eventStreamBaseClass);

        ParameterizedTypeName metadataMapperType = ParameterizedTypeName.get(
            ClassName.get(Function.class),
            ClassName.get(String.class),
            ParameterizedTypeName.get(Optional.class, ExceptionMetadata.class));

        builder.add("\n");
        builder.add("$T eventstreamExceptionMetadataMapper = errorCode -> {\n", metadataMapperType);
        builder.add("switch (errorCode) {\n");
        EventStreamUtils.getErrorMembers(eventStream).forEach(m -> {
            String errorCode = m.getC2jName();
            builder.add("case $S:\n", errorCode);
            builder.add("return $T.of($T.builder()", Optional.class, ExceptionMetadata.class);
            builder.add(".errorCode($S)", m.getShape().getErrorCode());
            builder.add(populateHttpStatusCode(m.getShape(), model));
            builder.add(".exceptionBuilderSupplier($T::builder).build());\n",
                        poetExtensions.getModelClassFromShape(m.getShape()));
        });
        builder.add("default: return $T.empty();", Optional.class);
        builder.add("}\n");
        builder.add("};\n");

        ParameterizedTypeName errorResponseHandlerType = ParameterizedTypeName.get(HttpResponseHandler.class,
                                                                                   AwsServiceException.class);

        builder.add("\n");
        builder.addStatement("$T errorEventResponseHandler = createErrorResponseHandler($N, operationMetadata, "
                             + "eventstreamExceptionMetadataMapper)",
                             errorResponseHandlerType,
                             protocolFactoryLiteral(model, opModel));

    }

    private String protocolFactoryLiteral(IntermediateModel model, OperationModel opModel) {
        // TODO remove this once kinesis supports CBOR for event streaming
        if ("Kinesis".equals(model.getMetadata().getServiceId()) && opModel.hasEventStreamOutput()) {
            return "jsonProtocolFactory";
        }

        return "protocolFactory";
    }

    private boolean isRestJson(IntermediateModel model) {
        return model.getMetadata().getProtocol() == Protocol.REST_JSON;
    }
}
