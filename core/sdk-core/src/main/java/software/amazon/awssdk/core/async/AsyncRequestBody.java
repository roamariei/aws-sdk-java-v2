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

package software.amazon.awssdk.core.async;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import software.amazon.awssdk.annotations.SdkProtectedApi;
import software.amazon.awssdk.annotations.SdkPublicApi;
import software.amazon.awssdk.core.FileRequestBodyConfiguration;
import software.amazon.awssdk.core.internal.async.ByteBuffersAsyncRequestBody;
import software.amazon.awssdk.core.internal.async.FileAsyncRequestBody;
import software.amazon.awssdk.core.internal.async.InputStreamWithExecutorAsyncRequestBody;
import software.amazon.awssdk.core.internal.async.SplittingPublisher;
import software.amazon.awssdk.core.internal.util.Mimetype;
import software.amazon.awssdk.utils.BinaryUtils;
import software.amazon.awssdk.utils.Validate;
import software.amazon.awssdk.utils.internal.EnumUtils;

/**
 * Interface to allow non-blocking streaming of request content. This follows the reactive streams pattern where this interface is
 * the {@link Publisher} of data (specifically {@link ByteBuffer} chunks) and the HTTP client is the Subscriber of the data (i.e.
 * to write that data on the wire).
 *
 * <p>
 * {@link #subscribe(Subscriber)} should be implemented to tie this publisher to a subscriber. Ideally each call to subscribe
 * should reproduce the content (i.e if you are reading from a file each subscribe call should produce a
 * {@link org.reactivestreams.Subscription} that reads the file fully). This allows for automatic retries to be performed in the
 * SDK. If the content is not reproducible,  an exception may be thrown from any subsequent {@link #subscribe(Subscriber)} calls.
 * </p>
 *
 * <p>
 * It is important to only send the number of chunks that the subscriber requests to avoid out of memory situations. The
 * subscriber does it's own buffering so it's usually not needed to buffer in the publisher. Additional permits for chunks will be
 * notified via the {@link org.reactivestreams.Subscription#request(long)} method.
 * </p>
 *
 * @see FileAsyncRequestBody
 * @see ByteBuffersAsyncRequestBody
 */
@SdkPublicApi
public interface AsyncRequestBody extends SdkPublisher<ByteBuffer> {

    /**
     * @return The content length of the data being produced.
     */
    Optional<Long> contentLength();

    /**
     * @return The content type of the data being produced.
     */
    default String contentType() {
        return Mimetype.MIMETYPE_OCTET_STREAM;
    }

    /**
     * Each AsyncRequestBody should return a well-formed name that can be used to identify the implementation.
     * The body name should only include alphanumeric characters.
     *
     * @return String containing the identifying name of this AsyncRequestBody implementation.
     */
    default String body() {
        return BodyType.UNKNOWN.getName();
    }

    /**
     * Creates an {@link AsyncRequestBody} the produces data from the input ByteBuffer publisher. The data is delivered when the
     * publisher publishes the data.
     *
     * @param publisher Publisher of source data
     * @return Implementation of {@link AsyncRequestBody} that produces data send by the publisher
     */
    static AsyncRequestBody fromPublisher(Publisher<ByteBuffer> publisher) {
        return new AsyncRequestBody() {

            /**
             * Returns empty optional as size of the each bytebuffer sent is unknown
             */
            @Override
            public Optional<Long> contentLength() {
                return Optional.empty();
            }

            @Override
            public void subscribe(Subscriber<? super ByteBuffer> s) {
                publisher.subscribe(s);
            }

            @Override
            public String body() {
                return BodyType.PUBLISHER.getName();
            }
        };
    }

    /**
     * Creates an {@link AsyncRequestBody} that produces data from the contents of a file. See
     * {@link FileAsyncRequestBody#builder} to create a customized body implementation.
     *
     * @param path Path to file to read from.
     * @return Implementation of {@link AsyncRequestBody} that reads data from the specified file.
     * @see FileAsyncRequestBody
     */
    static AsyncRequestBody fromFile(Path path) {
        return FileAsyncRequestBody.builder().path(path).build();
    }

    /**
     * Creates an {@link AsyncRequestBody} that produces data from the contents of a file. See
     * {@link #fromFile(FileRequestBodyConfiguration)} to create a customized body implementation.
     *
     * @param file The file to read from.
     * @return Implementation of {@link AsyncRequestBody} that reads data from the specified file.
     */
    static AsyncRequestBody fromFile(File file) {
        return FileAsyncRequestBody.builder().path(file.toPath()).build();
    }

    /**
     * Creates an {@link AsyncRequestBody} that produces data from the contents of a file.
     *
     * @param configuration configuration for how the SDK should read the file
     * @return Implementation of {@link AsyncRequestBody} that reads data from the specified file.
     */
    static AsyncRequestBody fromFile(FileRequestBodyConfiguration configuration) {
        Validate.notNull(configuration, "configuration");
        return FileAsyncRequestBody.builder()
                                   .path(configuration.path())
                                   .position(configuration.position())
                                   .chunkSizeInBytes(configuration.chunkSizeInBytes())
                                   .numBytesToRead(configuration.numBytesToRead())
                                   .build();
    }

    /**
     * Creates an {@link AsyncRequestBody} that produces data from the contents of a file.
     *
     * <p>
     * This is a convenience method that creates an instance of the {@link FileRequestBodyConfiguration} builder,
     * avoiding the need to create one manually via {@link FileRequestBodyConfiguration#builder()}.
     *
     * @param configuration configuration for how the SDK should read the file
     * @return Implementation of {@link AsyncRequestBody} that reads data from the specified file.
     */
    static AsyncRequestBody fromFile(Consumer<FileRequestBodyConfiguration.Builder> configuration) {
        Validate.notNull(configuration, "configuration");
        return fromFile(FileRequestBodyConfiguration.builder().applyMutation(configuration).build());
    }

    /**
     * Creates an {@link AsyncRequestBody} that uses a single string as data.
     *
     * @param string The string to provide.
     * @param cs The {@link Charset} to use.
     * @return Implementation of {@link AsyncRequestBody} that uses the specified string.
     * @see ByteBuffersAsyncRequestBody
     */
    static AsyncRequestBody fromString(String string, Charset cs) {
        return ByteBuffersAsyncRequestBody.from(Mimetype.MIMETYPE_TEXT_PLAIN + "; charset=" + cs.name(),
                                                string.getBytes(cs));
    }

    /**
     * Creates an {@link AsyncRequestBody} that uses a single string as data with UTF_8 encoding.
     *
     * @param string The string to send.
     * @return Implementation of {@link AsyncRequestBody} that uses the specified string.
     * @see #fromString(String, Charset)
     */
    static AsyncRequestBody fromString(String string) {
        return fromString(string, StandardCharsets.UTF_8);
    }

    /**
     * Creates an {@link AsyncRequestBody} from a byte array. This will copy the contents of the byte array to prevent
     * modifications to the provided byte array from being reflected in the {@link AsyncRequestBody}.
     *
     * @param bytes The bytes to send to the service.
     * @return AsyncRequestBody instance.
     */
    static AsyncRequestBody fromBytes(byte[] bytes) {
        byte[] clonedBytes = bytes.clone();
        return ByteBuffersAsyncRequestBody.from(clonedBytes);
    }

    /**
     * Creates an {@link AsyncRequestBody} from a byte array <b>without</b> copying the contents of the byte array. This
     * introduces concurrency risks, allowing: (1) the caller to modify the byte array stored in this {@code AsyncRequestBody}
     * implementation AND (2) any users of {@link #fromBytesUnsafe(byte[])} to modify the byte array passed into this
     * {@code AsyncRequestBody} implementation.
     *
     * <p>As the method name implies, this is unsafe. Use {@link #fromBytes(byte[])} unless you're sure you know the risks.
     *
     * @param bytes The bytes to send to the service.
     * @return AsyncRequestBody instance.
     */
    static AsyncRequestBody fromBytesUnsafe(byte[] bytes) {
        return ByteBuffersAsyncRequestBody.from(bytes);
    }

    /**
     * Creates an {@link AsyncRequestBody} from a {@link ByteBuffer}. This will copy the contents of the {@link ByteBuffer} to
     * prevent modifications to the provided {@link ByteBuffer} from being reflected in the {@link AsyncRequestBody}.
     * <p>
     * <b>NOTE:</b> This method ignores the current read position. Use {@link #fromRemainingByteBuffer(ByteBuffer)} if you need
     * it to copy only the remaining readable bytes.
     *
     * @param byteBuffer ByteBuffer to send to the service.
     * @return AsyncRequestBody instance.
     */
    static AsyncRequestBody fromByteBuffer(ByteBuffer byteBuffer) {
        ByteBuffer immutableCopy = BinaryUtils.immutableCopyOf(byteBuffer);
        immutableCopy.rewind();
        return ByteBuffersAsyncRequestBody.of((long) immutableCopy.remaining(), immutableCopy);
    }

    /**
     * Creates an {@link AsyncRequestBody} from the remaining readable bytes from a {@link ByteBuffer}. This will copy the
     * remaining contents of the {@link ByteBuffer} to prevent modifications to the provided {@link ByteBuffer} from being
     * reflected in the {@link AsyncRequestBody}.
     * <p> Unlike {@link #fromByteBuffer(ByteBuffer)}, this method respects the current read position of the buffer and reads
     * only the remaining bytes.
     *
     * @param byteBuffer ByteBuffer to send to the service.
     * @return AsyncRequestBody instance.
     */
    static AsyncRequestBody fromRemainingByteBuffer(ByteBuffer byteBuffer) {
        ByteBuffer immutableCopy = BinaryUtils.immutableCopyOfRemaining(byteBuffer);
        return ByteBuffersAsyncRequestBody.of((long) immutableCopy.remaining(), immutableCopy);
    }

    /**
     * Creates an {@link AsyncRequestBody} from a {@link ByteBuffer} <b>without</b> copying the contents of the
     * {@link ByteBuffer}. This introduces concurrency risks, allowing the caller to modify the {@link ByteBuffer} stored in this
     * {@code AsyncRequestBody} implementation.
     * <p>
     * <b>NOTE:</b> This method ignores the current read position. Use {@link #fromRemainingByteBufferUnsafe(ByteBuffer)} if you
     * need it to copy only the remaining readable bytes.
     *
     * <p>As the method name implies, this is unsafe. Use {@link #fromByteBuffer(ByteBuffer)}} unless you're sure you know the
     * risks.
     *
     * @param byteBuffer ByteBuffer to send to the service.
     * @return AsyncRequestBody instance.
     */
    static AsyncRequestBody fromByteBufferUnsafe(ByteBuffer byteBuffer) {
        ByteBuffer readOnlyBuffer = byteBuffer.asReadOnlyBuffer();
        readOnlyBuffer.rewind();
        return ByteBuffersAsyncRequestBody.of((long) readOnlyBuffer.remaining(), readOnlyBuffer);
    }

    /**
     * Creates an {@link AsyncRequestBody} from a {@link ByteBuffer} <b>without</b> copying the contents of the
     * {@link ByteBuffer}. This introduces concurrency risks, allowing the caller to modify the {@link ByteBuffer} stored in this
     * {@code AsyncRequestBody} implementation.
     * <p>Unlike {@link #fromByteBufferUnsafe(ByteBuffer)}, this method respects the current read position of
     * the buffer and reads only the remaining bytes.
     *
     * <p>As the method name implies, this is unsafe. Use {@link #fromByteBuffer(ByteBuffer)}} unless you're sure you know the
     * risks.
     *
     * @param byteBuffer ByteBuffer to send to the service.
     * @return AsyncRequestBody instance.
     */
    static AsyncRequestBody fromRemainingByteBufferUnsafe(ByteBuffer byteBuffer) {
        ByteBuffer readOnlyBuffer = byteBuffer.asReadOnlyBuffer();
        return ByteBuffersAsyncRequestBody.of((long) readOnlyBuffer.remaining(), readOnlyBuffer);
    }

    /**
     * Creates an {@link AsyncRequestBody} from a {@link ByteBuffer} array. This will copy the contents of each {@link ByteBuffer}
     * to prevent modifications to any provided {@link ByteBuffer} from being reflected in the {@link AsyncRequestBody}.
     * <p>
     * <b>NOTE:</b> This method ignores the current read position of each {@link ByteBuffer}. Use
     * {@link #fromRemainingByteBuffers(ByteBuffer...)} if you need it to copy only the remaining readable bytes.
     *
     * @param byteBuffers ByteBuffer array to send to the service.
     * @return AsyncRequestBody instance.
     */
    static AsyncRequestBody fromByteBuffers(ByteBuffer... byteBuffers) {
        ByteBuffer[] immutableCopy = Arrays.stream(byteBuffers)
                                           .map(BinaryUtils::immutableCopyOf)
                                           .peek(ByteBuffer::rewind)
                                           .toArray(ByteBuffer[]::new);
        return ByteBuffersAsyncRequestBody.of(immutableCopy);
    }

    /**
     * Creates an {@link AsyncRequestBody} from a {@link ByteBuffer} array. This will copy the remaining contents of each
     * {@link ByteBuffer} to prevent modifications to any provided {@link ByteBuffer} from being reflected in the
     * {@link AsyncRequestBody}.
     * <p>Unlike {@link #fromByteBufferUnsafe(ByteBuffer)},
     * this method respects the current read position of each buffer and reads only the remaining bytes.
     *
     * @param byteBuffers ByteBuffer array to send to the service.
     * @return AsyncRequestBody instance.
     */
    static AsyncRequestBody fromRemainingByteBuffers(ByteBuffer... byteBuffers) {
        ByteBuffer[] immutableCopy = Arrays.stream(byteBuffers)
                                           .map(BinaryUtils::immutableCopyOfRemaining)
                                           .peek(ByteBuffer::rewind)
                                           .toArray(ByteBuffer[]::new);
        return ByteBuffersAsyncRequestBody.of(immutableCopy);
    }

    /**
     * Creates an {@link AsyncRequestBody} from a {@link ByteBuffer} array <b>without</b> copying the contents of each
     * {@link ByteBuffer}. This introduces concurrency risks, allowing the caller to modify any {@link ByteBuffer} stored in this
     * {@code AsyncRequestBody} implementation.
     * <p>
     * <b>NOTE:</b> This method ignores the current read position of each {@link ByteBuffer}. Use
     * {@link #fromRemainingByteBuffers(ByteBuffer...)} if you need it to copy only the remaining readable bytes.
     *
     * <p>As the method name implies, this is unsafe. Use {@link #fromByteBuffers(ByteBuffer...)} unless you're sure you know the
     * risks.
     *
     * @param byteBuffers ByteBuffer array to send to the service.
     * @return AsyncRequestBody instance.
     */
    static AsyncRequestBody fromByteBuffersUnsafe(ByteBuffer... byteBuffers) {
        ByteBuffer[] readOnlyBuffers = Arrays.stream(byteBuffers)
                                             .map(ByteBuffer::asReadOnlyBuffer)
                                             .peek(ByteBuffer::rewind)
                                             .toArray(ByteBuffer[]::new);
        return ByteBuffersAsyncRequestBody.of(readOnlyBuffers);
    }

    /**
     * Creates an {@link AsyncRequestBody} from a {@link ByteBuffer} array <b>without</b> copying the contents of each
     * {@link ByteBuffer}. This introduces concurrency risks, allowing the caller to modify any {@link ByteBuffer} stored in this
     * {@code AsyncRequestBody} implementation.
     * <p>Unlike {@link #fromByteBuffersUnsafe(ByteBuffer...)},
     * this method respects the current read position of each buffer and reads only the remaining bytes.
     *
     * <p>As the method name implies, this is unsafe. Use {@link #fromByteBuffers(ByteBuffer...)} unless you're sure you know the
     * risks.
     *
     * @param byteBuffers ByteBuffer array to send to the service.
     * @return AsyncRequestBody instance.
     */
    static AsyncRequestBody fromRemainingByteBuffersUnsafe(ByteBuffer... byteBuffers) {
        ByteBuffer[] readOnlyBuffers = Arrays.stream(byteBuffers)
                                             .map(ByteBuffer::asReadOnlyBuffer)
                                             .toArray(ByteBuffer[]::new);
        return ByteBuffersAsyncRequestBody.of(readOnlyBuffers);
    }

    /**
     * Creates an {@link AsyncRequestBody} from an {@link InputStream}.
     *
     * <p>An {@link ExecutorService} is required in order to perform the blocking data reads, to prevent blocking the
     * non-blocking event loop threads owned by the SDK.
     *
     * @param inputStream The input stream containing the data to be sent
     * @param contentLength The content length. If a content length smaller than the actual size of the object is set, the client
     *                      will truncate the stream to the specified content length and only send exactly the number of bytes
     *                      equal to the content length.
     * @param executor The executor
     *
     * @return An AsyncRequestBody instance for the input stream
     *
     */
    static AsyncRequestBody fromInputStream(InputStream inputStream, Long contentLength, ExecutorService executor) {
        return fromInputStream(b -> b.inputStream(inputStream).contentLength(contentLength).executor(executor));
    }

    /**
     * Creates an {@link AsyncRequestBody} from an {@link InputStream} with the provided
     * {@link AsyncRequestBodySplitConfiguration}.
     */
    static AsyncRequestBody fromInputStream(AsyncRequestBodyFromInputStreamConfiguration configuration) {
        Validate.notNull(configuration, "configuration");
        return new InputStreamWithExecutorAsyncRequestBody(configuration);
    }

    /**
     * This is a convenience method that passes an instance of the {@link AsyncRequestBodyFromInputStreamConfiguration} builder,
     * avoiding the need to create one manually via {@link AsyncRequestBodyFromInputStreamConfiguration#builder()}.
     *
     * @see #fromInputStream(AsyncRequestBodyFromInputStreamConfiguration)
     */
    static AsyncRequestBody fromInputStream(Consumer<AsyncRequestBodyFromInputStreamConfiguration.Builder> configuration) {
        Validate.notNull(configuration, "configuration");
        return fromInputStream(AsyncRequestBodyFromInputStreamConfiguration.builder().applyMutation(configuration).build());
    }

    /**
     * Creates a {@link BlockingInputStreamAsyncRequestBody} to use for writing an input stream to the downstream service.
     *
     * <p>By default, it will time out if streaming hasn't started within 10 seconds, and use application/octet-stream as
     * content type. You can configure it via {@link BlockingInputStreamAsyncRequestBody#builder()}
     *
     * <p><b>Example Usage</b>
     *
     * <p>
     * {@snippet :
     *     S3AsyncClient s3 = S3AsyncClient.create(); // Use one client for your whole application!
     *
     *     byte[] dataToSend = "Hello".getBytes(StandardCharsets.UTF_8);
     *     InputStream streamToSend = new ByteArrayInputStream(dataToSend);
     *     long streamToSendLength = dataToSend.length;
     *
     *     // Start the operation
     *     BlockingInputStreamAsyncRequestBody body =
     *         AsyncRequestBody.forBlockingInputStream(streamToSendLength);
     *     CompletableFuture<PutObjectResponse> responseFuture =
     *         s3.putObject(r -> r.bucket("bucketName").key("key"), body);
     *
     *     // Write the input stream to the running operation
     *     body.writeInputStream(streamToSend);
     *
     *     // Wait for the service to respond.
     *     PutObjectResponse response = responseFuture.join();
     * }
     * @param contentLength The content length. If a content length smaller than the actual size of the object is set, the client
     *                      will truncate the stream to the specified content length and only send exactly the number of bytes
     *                      equal to the content length.
     * @return The created {@code BlockingInputStreamAsyncRequestBody}.
     */
    static BlockingInputStreamAsyncRequestBody forBlockingInputStream(Long contentLength) {
        return BlockingInputStreamAsyncRequestBody.builder()
                                                  .contentLength(contentLength)
                                                  .build();
    }

    /**
     * Creates a {@link BlockingOutputStreamAsyncRequestBody} to use for writing to the downstream service as if it's an output
     * stream. Retries are not supported for this request body.
     *
     * <p>The caller is responsible for calling {@link OutputStream#close()} on the
     * {@link BlockingOutputStreamAsyncRequestBody#outputStream()} when writing is complete.
     *
     * <p>By default, it will time out if streaming hasn't started within 10 seconds, and you can configure the timeout
     * via {@link BlockingOutputStreamAsyncRequestBody#builder()}
     * <p><b>Example Usage</b>
     * <p>
     * {@snippet :
     *     S3AsyncClient s3 = S3AsyncClient.create(); // Use one client for your whole application!
     *
     *     byte[] dataToSend = "Hello".getBytes(StandardCharsets.UTF_8);
     *     long lengthOfDataToSend = dataToSend.length();
     *
     *     // Start the operation
     *     BlockingInputStreamAsyncRequestBody body =
     *         AsyncRequestBody.forBlockingOutputStream(lengthOfDataToSend);
     *     CompletableFuture<PutObjectResponse> responseFuture =
     *         s3.putObject(r -> r.bucket("bucketName").key("key"), body);
     *
     *     // Write the input stream to the running operation
     *     try (CancellableOutputStream outputStream = body.outputStream()) {
     *         outputStream.write(dataToSend);
     *     }
     *
     *     // Wait for the service to respond.
     *     PutObjectResponse response = responseFuture.join();
     * }
     * @see BlockingOutputStreamAsyncRequestBody
     *
     * @param contentLength The content length. If a content length smaller than the actual size of the object is set, the client
     *                      will truncate the stream to the specified content length and only send exactly the number of bytes
     *                      equal to the content length.
     * @return The created {@code BlockingOutputStreamAsyncRequestBody}.
     */
    static BlockingOutputStreamAsyncRequestBody forBlockingOutputStream(Long contentLength) {
        return BlockingOutputStreamAsyncRequestBody.builder()
                                                   .contentLength(contentLength)
                                                   .build();
    }

    /**
     * Creates an {@link AsyncRequestBody} with no content.
     *
     * @return AsyncRequestBody instance.
     */
    static AsyncRequestBody empty() {
        return fromBytes(new byte[0]);
    }


    /**
     * Converts this {@link AsyncRequestBody} to a publisher of {@link AsyncRequestBody}s, each of which publishes a specific
     * portion of the original data, based on the provided {@link AsyncRequestBodySplitConfiguration}. The default chunk size
     * is 2MB and the default buffer size is 8MB.
     *
     * <p>
     * By default, if content length of this {@link AsyncRequestBody} is present, each divided {@link AsyncRequestBody} is
     * delivered to the subscriber right after it's initialized. On the other hand, if content length is null, it is sent after
     * the entire content for that chunk is buffered. In this case, the configured {@code maxMemoryUsageInBytes} must be larger
     * than or equal to {@code chunkSizeInBytes}. Note that this behavior may be different if a specific implementation of this
     * interface overrides this method.
     *
     * @see AsyncRequestBodySplitConfiguration
     */
    default SdkPublisher<AsyncRequestBody> split(AsyncRequestBodySplitConfiguration splitConfiguration) {
        Validate.notNull(splitConfiguration, "splitConfiguration");

        return new SplittingPublisher(this, splitConfiguration);
    }

    /**
     * This is a convenience method that passes an instance of the {@link AsyncRequestBodySplitConfiguration} builder,
     * avoiding the need to create one manually via {@link AsyncRequestBodySplitConfiguration#builder()}.
     *
     * @see #split(AsyncRequestBodySplitConfiguration)
     */
    default SdkPublisher<AsyncRequestBody> split(Consumer<AsyncRequestBodySplitConfiguration.Builder> splitConfiguration) {
        Validate.notNull(splitConfiguration, "splitConfiguration");
        return split(AsyncRequestBodySplitConfiguration.builder().applyMutation(splitConfiguration).build());
    }

    @SdkProtectedApi
    enum BodyType {
        FILE("File", "f"),
        BYTES("Bytes", "b"),
        STREAM("Stream", "s"),
        PUBLISHER("Publisher", "p"),
        UNKNOWN("Unknown", "u");

        private static final Map<String, BodyType> VALUE_MAP =
            EnumUtils.uniqueIndex(BodyType.class, BodyType::getName);

        private final String name;
        private final String shortValue;

        BodyType(String name, String shortValue) {
            this.name = name;
            this.shortValue = shortValue;
        }

        public String getName() {
            return name;
        }

        public String getShortValue() {
            return shortValue;
        }

        public static String shortValueFromName(String name) {
            return VALUE_MAP.getOrDefault(name, UNKNOWN).getShortValue();
        }
    }
}
