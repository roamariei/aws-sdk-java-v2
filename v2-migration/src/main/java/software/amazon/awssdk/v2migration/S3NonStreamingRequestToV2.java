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

package software.amazon.awssdk.v2migration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.MethodMatcher;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JContainer;
import org.openrewrite.java.tree.JRightPadded;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Space;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.marker.Markers;
import software.amazon.awssdk.annotations.SdkInternalApi;
import software.amazon.awssdk.v2migration.internal.utils.IdentifierUtils;

@SdkInternalApi
public class S3NonStreamingRequestToV2 extends Recipe {

    private static final MethodMatcher DELETE_VERSION =
        new MethodMatcher("com.amazonaws.services.s3.AmazonS3 deleteVersion(String, String, String)", true);
    private static final MethodMatcher COPY_OBJECT =
        new MethodMatcher("com.amazonaws.services.s3.AmazonS3 copyObject(String, String, String, String)", true);
    private static final MethodMatcher LIST_VERSIONS =
        new MethodMatcher("com.amazonaws.services.s3.AmazonS3 "
                          + "listVersions(String, String, String, String, String, Integer)", true);
    private static final MethodMatcher SET_BUCKET_POLICY =
        new MethodMatcher("com.amazonaws.services.s3.AmazonS3 setBucketPolicy(String, String)", true);

    private static final Map<MethodMatcher, JavaType.FullyQualified> BUCKET_ARG_METHODS = new HashMap<>();
    private static final Map<MethodMatcher, JavaType.FullyQualified> BUCKET_KEY_ARGS_METHODS = new HashMap<>();
    private static final Map<MethodMatcher, JavaType.FullyQualified> BUCKET_ID_ARGS_METHODS = new HashMap<>();
    private static final Map<MethodMatcher, JavaType.FullyQualified> BUCKET_PREFIX_ARGS_METHODS = new HashMap<>();

    static {
        BUCKET_ARG_METHODS.put(singleStringArgMethod("createBucket"), fcqn("createBucket"));
        BUCKET_ARG_METHODS.put(singleStringArgMethod("deleteBucket"), fcqn("deleteBucket"));
        BUCKET_ARG_METHODS.put(singleStringArgMethod("listObjects"), fcqn("listObjects"));
        BUCKET_ARG_METHODS.put(singleStringArgMethod("listObjectsV2"), fcqn("listObjectsV2"));
        BUCKET_ARG_METHODS.put(singleStringArgMethod("getBucketCrossOriginConfiguration"),
                               fcqn("getBucketCrossOriginConfiguration"));
        BUCKET_ARG_METHODS.put(singleStringArgMethod("deleteBucketCrossOriginConfiguration"),
                               fcqn("deleteBucketCrossOriginConfiguration"));
        BUCKET_ARG_METHODS.put(singleStringArgMethod("getBucketVersioningConfiguration"),
                               fcqn("getBucketVersioningConfiguration"));
        BUCKET_ARG_METHODS.put(singleStringArgMethod("deleteBucketEncryption"), fcqn("deleteBucketEncryption"));
        BUCKET_ARG_METHODS.put(singleStringArgMethod("deleteBucketPolicy"), fcqn("deleteBucketPolicy"));
        BUCKET_ARG_METHODS.put(singleStringArgMethod("getBucketAccelerateConfiguration"),
                               fcqn("getBucketAccelerateConfiguration"));
        BUCKET_ARG_METHODS.put(singleStringArgMethod("getBucketAcl"), fcqn("getBucketAcl"));
        BUCKET_ARG_METHODS.put(singleStringArgMethod("getBucketEncryption"), fcqn("getBucketEncryption"));
        BUCKET_ARG_METHODS.put(singleStringArgMethod("getBucketLifecycleConfiguration"),
                               fcqn("getBucketLifecycleConfiguration"));
        BUCKET_ARG_METHODS.put(singleStringArgMethod("getBucketNotificationConfiguration"),
                               fcqn("getBucketNotificationConfiguration"));
        BUCKET_ARG_METHODS.put(singleStringArgMethod("getBucketPolicy"), fcqn("getBucketPolicy"));
        BUCKET_ARG_METHODS.put(singleStringArgMethod("getBucketLocation"), fcqn("getBucketLocation"));
        BUCKET_ARG_METHODS.put(singleStringArgMethod("deleteBucketLifecycleConfiguration"),
                               fcqn("deleteBucketLifecycleConfiguration"));
        BUCKET_ARG_METHODS.put(singleStringArgMethod("deleteBucketReplicationConfiguration"),
                               fcqn("deleteBucketReplicationConfiguration"));
        BUCKET_ARG_METHODS.put(singleStringArgMethod("deleteBucketTaggingConfiguration"),
                               fcqn("deleteBucketTaggingConfiguration"));
        BUCKET_ARG_METHODS.put(singleStringArgMethod("deleteBucketWebsiteConfiguration"),
                               fcqn("deleteBucketWebsiteConfiguration"));
        BUCKET_ARG_METHODS.put(singleStringArgMethod("getBucketLoggingConfiguration"), fcqn("getBucketLoggingConfiguration"));
        BUCKET_ARG_METHODS.put(singleStringArgMethod("getBucketReplicationConfiguration"),
                               fcqn("getBucketReplicationConfiguration"));
        BUCKET_ARG_METHODS.put(singleStringArgMethod("getBucketTaggingConfiguration"), fcqn("getBucketTaggingConfiguration"));
        BUCKET_ARG_METHODS.put(singleStringArgMethod("getBucketWebsiteConfiguration"), fcqn("getBucketWebsiteConfiguration"));

        BUCKET_KEY_ARGS_METHODS.put(twoStringArgsMethod("deleteObject"), fcqn("deleteObject"));
        BUCKET_KEY_ARGS_METHODS.put(twoStringArgsMethod("getObject"), fcqn("getObject"));
        BUCKET_KEY_ARGS_METHODS.put(twoStringArgsMethod("getObjectAcl"), fcqn("getObjectAcl"));
        BUCKET_KEY_ARGS_METHODS.put(twoStringArgsMethod("getObjectMetadata"), fcqn("getObjectMetadata"));

        BUCKET_ID_ARGS_METHODS.put(twoStringArgsMethod("deleteBucketAnalyticsConfiguration"),
                                   fcqn("deleteBucketAnalyticsConfiguration"));
        BUCKET_ID_ARGS_METHODS.put(twoStringArgsMethod("deleteBucketIntelligentTieringConfiguration"),
                                   fcqn("deleteBucketIntelligentTieringConfiguration"));
        BUCKET_ID_ARGS_METHODS.put(twoStringArgsMethod("deleteBucketInventoryConfiguration"),
                                   fcqn("deleteBucketInventoryConfiguration"));
        BUCKET_ID_ARGS_METHODS.put(twoStringArgsMethod("deleteBucketMetricsConfiguration"),
                                   fcqn("deleteBucketMetricsConfiguration"));
        BUCKET_ID_ARGS_METHODS.put(twoStringArgsMethod("getBucketAnalyticsConfiguration"),
                                   fcqn("getBucketAnalyticsConfiguration"));
        BUCKET_ID_ARGS_METHODS.put(twoStringArgsMethod("getBucketIntelligentTieringConfiguration"),
                                   fcqn("getBucketIntelligentTieringConfiguration"));
        BUCKET_ID_ARGS_METHODS.put(twoStringArgsMethod("getBucketInventoryConfiguration"),
                                   fcqn("getBucketInventoryConfiguration"));
        BUCKET_ID_ARGS_METHODS.put(twoStringArgsMethod("getBucketMetricsConfiguration"), fcqn("getBucketMetricsConfiguration"));

        BUCKET_PREFIX_ARGS_METHODS.put(twoStringArgsMethod("listObjects"), fcqn("listObjects"));
        BUCKET_PREFIX_ARGS_METHODS.put(twoStringArgsMethod("listObjectsV2"), fcqn("listObjectsV2"));
        BUCKET_PREFIX_ARGS_METHODS.put(twoStringArgsMethod("listVersions"), fcqn("listVersions"));
    }

    private static MethodMatcher singleStringArgMethod(String method) {
        String signature = "com.amazonaws.services.s3.AmazonS3 " + method + "(java.lang.String)";
        return new MethodMatcher(signature,  true);
    }

    private static MethodMatcher twoStringArgsMethod(String method) {
        String signature = "com.amazonaws.services.s3.AmazonS3 " + method + "(java.lang.String, java.lang.String)";
        return new MethodMatcher(signature,  true);
    }

    private static JavaType.FullyQualified fcqn(String method) {
        String methodFirstLetterCaps = method.substring(0, 1).toUpperCase(Locale.ROOT) + method.substring(1);
        String typeName = "com.amazonaws.services.s3.model." + methodFirstLetterCaps + "Request";
        return TypeUtils.asFullyQualified(JavaType.buildType(typeName));
    }

    @Override
    public String getDisplayName() {
        return "V1 S3 non-streaming requests to V2";
    }

    @Override
    public String getDescription() {
        return "Transform usage of V1 S3 non-streaming requests to V2.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new Visitor();
    }

    private static final class Visitor extends JavaIsoVisitor<ExecutionContext> {
        @Override
        public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext executionContext) {

            if (DELETE_VERSION.matches(method)) {
                method = transformMethod(method, fcqn("deleteObject"), "bucket", "key", "versionId");
                return super.visitMethodInvocation(method, executionContext);
            }
            if (COPY_OBJECT.matches(method)) {
                method = transformMethod(method, fcqn("copyObject"),
                                         "sourceBucket", "sourceKey", "destinationBucket", "destinationKey");
                return super.visitMethodInvocation(method, executionContext);
            }
            if (LIST_VERSIONS.matches(method)) {
                method = transformMethod(method, fcqn("listVersions"),
                                         "bucket", "prefix", "keyMarker", "versionIdMarker", "delimiter", "maxKeys");
                return super.visitMethodInvocation(method, executionContext);
            }
            if (SET_BUCKET_POLICY.matches(method)) {
                method = transformMethod(method, fcqn("putBucketPolicy"), "bucket", "policy");
                return super.visitMethodInvocation(method, executionContext);
            }
            for (Map.Entry<MethodMatcher, JavaType.FullyQualified> entry : BUCKET_ARG_METHODS.entrySet()) {
                if (entry.getKey().matches(method)) {
                    method = transformMethod(method, entry.getValue(), "bucket");
                    return super.visitMethodInvocation(method, executionContext);
                }
            }
            for (Map.Entry<MethodMatcher, JavaType.FullyQualified> entry : BUCKET_KEY_ARGS_METHODS.entrySet()) {
                if (entry.getKey().matches(method)) {
                    method = transformMethod(method, entry.getValue(), "bucket", "key");
                    return super.visitMethodInvocation(method, executionContext);
                }
            }
            for (Map.Entry<MethodMatcher, JavaType.FullyQualified> entry : BUCKET_ID_ARGS_METHODS.entrySet()) {
                if (entry.getKey().matches(method)) {
                    method = transformMethod(method, entry.getValue(), "bucket", "id");
                    return super.visitMethodInvocation(method, executionContext);
                }
            }
            for (Map.Entry<MethodMatcher, JavaType.FullyQualified> entry : BUCKET_PREFIX_ARGS_METHODS.entrySet()) {
                if (entry.getKey().matches(method)) {
                    method = transformMethod(method, entry.getValue(), "bucket", "prefix");
                    return super.visitMethodInvocation(method, executionContext);
                }
            }

            return super.visitMethodInvocation(method, executionContext);
        }

        private J.MethodInvocation transformMethod(J.MethodInvocation method, JavaType.FullyQualified fqcn,
                                                                 String... args) {
            JavaType.Method methodType = method.getMethodType();
            if (methodType == null) {
                return method;
            }

            List<String> names = Arrays.asList(args);
            List<JavaType> types = new ArrayList<>();
            List<JRightPadded<Expression>> expressions = new ArrayList<>();

            for (int i = 0; i < names.size(); i++) {
                Expression expr = method.getArguments().get(i);
                types.add(expr.getType());
                expressions.add(JRightPadded.build(expr));
            }

            Expression newPojo = argsToPojo(fqcn, names, types, JContainer.build(expressions));
            List<Expression> newArgs = Collections.singletonList(newPojo);
            methodType = addParamsToMethod(methodType, newArgs);
            return method.withMethodType(methodType).withArguments(newArgs);
        }

        private JavaType.Method addParamsToMethod(JavaType.Method methodType, List<Expression> newArgs) {
            List<String> paramNames = Collections.singletonList("request");
            List<JavaType> paramTypes = newArgs.stream()
                                               .map(Expression::getType)
                                               .collect(Collectors.toList());

            return methodType.withParameterTypes(paramTypes)
                             .withParameterNames(paramNames);
        }

        private Expression argsToPojo(JavaType.FullyQualified fqcn, List<String> names, List<JavaType> types,
                                      JContainer<Expression> args) {
            maybeAddImport(fqcn);

            J.Identifier requestId = IdentifierUtils.makeId(fqcn.getClassName(), fqcn);

            JavaType.Method ctorType = new JavaType.Method(
                null,
                0L,
                fqcn,
                "<init>",
                fqcn,
                names,
                types,
                null,
                null
            );

            return new J.NewClass(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                null,
                Space.EMPTY,
                requestId.withPrefix(Space.SINGLE_SPACE),
                args,
                null,
                ctorType
            );
        }
    }
}
