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

package software.amazon.awssdk.core.internal.async;

import static software.amazon.awssdk.core.FileTransformerConfiguration.FileWriteOption.CREATE_OR_APPEND_TO_EXISTING;
import static software.amazon.awssdk.core.FileTransformerConfiguration.FileWriteOption.WRITE_TO_POSITION;
import static software.amazon.awssdk.utils.FunctionalUtils.invokeSafely;
import static software.amazon.awssdk.utils.FunctionalUtils.runAndLogError;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.CompletionHandler;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import software.amazon.awssdk.annotations.SdkInternalApi;
import software.amazon.awssdk.core.FileTransformerConfiguration;
import software.amazon.awssdk.core.FileTransformerConfiguration.FailureBehavior;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.core.async.SdkPublisher;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.utils.Logger;
import software.amazon.awssdk.utils.Validate;

/**
 * {@link AsyncResponseTransformer} that writes the data to the specified file.
 *
 * @param <ResponseT> Response POJO type.
 */
@SdkInternalApi
public final class FileAsyncResponseTransformer<ResponseT> implements AsyncResponseTransformer<ResponseT, ResponseT> {
    private static final Logger log = Logger.loggerFor(FileAsyncResponseTransformer.class);
    private final Path path;
    private volatile AsynchronousFileChannel fileChannel;
    private volatile CompletableFuture<Void> cf;
    private volatile ResponseT response;
    private final long position;
    private final FileTransformerConfiguration configuration;

    public FileAsyncResponseTransformer(Path path) {
        this(path, FileTransformerConfiguration.defaultCreateNew(), 0L);
    }

    public FileAsyncResponseTransformer(Path path, FileTransformerConfiguration fileConfiguration) {
        this(path, fileConfiguration, determineFilePositionToWrite(path, fileConfiguration));
    }

    private FileAsyncResponseTransformer(Path path, FileTransformerConfiguration fileTransformerConfiguration, long position) {
        this.path = path;
        this.configuration = fileTransformerConfiguration;
        this.position = position;
    }

    private static long determineFilePositionToWrite(Path path, FileTransformerConfiguration fileConfiguration) {
        if (fileConfiguration.fileWriteOption() == CREATE_OR_APPEND_TO_EXISTING) {
            try {
                return Files.size(path);
            } catch (NoSuchFileException e) {
                // Ignore
            } catch (IOException exception) {
                throw SdkClientException.create("Cannot determine the current file size " + path, exception);
            }
        }
        if (fileConfiguration.fileWriteOption() == WRITE_TO_POSITION) {
            return Validate.getOrDefault(fileConfiguration.position(), () -> 0L);
        }
        return  0L;
    }

    private AsynchronousFileChannel createChannel(Path path) throws IOException {
        Set<OpenOption> options = new HashSet<>();
        switch (configuration.fileWriteOption()) {
            case CREATE_OR_APPEND_TO_EXISTING:
                Collections.addAll(options, StandardOpenOption.WRITE, StandardOpenOption.CREATE);
                break;
            case CREATE_OR_REPLACE_EXISTING:
                Collections.addAll(options, StandardOpenOption.WRITE, StandardOpenOption.CREATE,
                                   StandardOpenOption.TRUNCATE_EXISTING);
                break;
            case CREATE_NEW:
                Collections.addAll(options, StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW);
                break;
            case WRITE_TO_POSITION:
                Collections.addAll(options, StandardOpenOption.WRITE);
                break;
            default:
                throw new IllegalArgumentException("Unsupported file write option: " + configuration.fileWriteOption());
        }

        ExecutorService executorService = configuration.executorService().orElse(null);
        return AsynchronousFileChannel.open(path, options, executorService);
    }

    @Override
    public CompletableFuture<ResponseT> prepare() {
        cf = new CompletableFuture<>();
        cf.whenComplete((r, t) -> {
            if (t != null && fileChannel != null) {
                runAndLogError(log.logger(),
                               String.format("Failed to close the file %s, resource may be leaked", path),
                               () -> fileChannel.close());
            }
        });
        return cf.thenApply(ignored -> response);
    }

    @Override
    public void onResponse(ResponseT response) {
        this.response = response;
    }

    @Override
    public void onStream(SdkPublisher<ByteBuffer> publisher) {
        try {
            // onStream may be called multiple times so reset the file channel every time
            this.fileChannel = createChannel(path);
            publisher.subscribe(new FileSubscriber(this.fileChannel, path, cf, this::exceptionOccurred,
                                                   position));
        } catch (Throwable e) {
            exceptionOccurred(e);
        }
    }

    @Override
    public void exceptionOccurred(Throwable throwable) {
        try {
            if (fileChannel != null) {
                runAndLogError(log.logger(),
                               String.format("Failed to close the file %s, resource may be leaked", path),
                               () -> fileChannel.close());
            }
        } finally {
            if (configuration.failureBehavior() == FailureBehavior.DELETE) {
                runAndLogError(log.logger(),
                               String.format("Failed to delete the file %s", path),
                               () -> Files.deleteIfExists(path));
            }
        }
        if (cf != null) {
            cf.completeExceptionally(throwable);
        } else {
            log.warn(() -> "An exception occurred before the call to prepare() was able to instantiate the CompletableFuture."
                           + "The future cannot be completed exceptionally because it is null");
        }
    }

    @Override
    public String name() {
        return TransformerType.FILE.getName();
    }

    /**
     * {@link Subscriber} implementation that writes chunks to a file.
     */
    static class FileSubscriber implements Subscriber<ByteBuffer> {
        private final AtomicLong position;
        private final AsynchronousFileChannel fileChannel;
        private final Path path;
        private final CompletableFuture<Void> future;
        private final Consumer<Throwable> onErrorMethod;

        private volatile boolean writeInProgress = false;
        private volatile boolean closeOnLastWrite = false;
        private Subscription subscription;

        FileSubscriber(AsynchronousFileChannel fileChannel, Path path, CompletableFuture<Void> future,
                       Consumer<Throwable> onErrorMethod, long startingPosition) {
            this.fileChannel = fileChannel;
            this.path = path;
            this.future = future;
            this.onErrorMethod = onErrorMethod;
            this.position = new AtomicLong(startingPosition);
        }

        @Override
        public void onSubscribe(Subscription s) {
            if (this.subscription != null) {
                s.cancel();
                return;
            }
            this.subscription = s;
            // Request the first chunk to start producing content
            s.request(1);
        }

        @Override
        public void onNext(ByteBuffer byteBuffer) {
            if (byteBuffer == null) {
                throw new NullPointerException("Element must not be null");
            }

            performWrite(byteBuffer);
        }

        private void performWrite(ByteBuffer byteBuffer) {
            writeInProgress = true;

            fileChannel.write(byteBuffer, position.get(), byteBuffer, new CompletionHandler<Integer, ByteBuffer>() {
                @Override
                public void completed(Integer result, ByteBuffer attachment) {
                    position.addAndGet(result);

                    if (byteBuffer.hasRemaining()) {
                        performWrite(byteBuffer);
                    } else {
                        synchronized (FileSubscriber.this) {
                            writeInProgress = false;
                            if (closeOnLastWrite) {
                                close();
                            } else {
                                subscription.request(1);
                            }
                        }
                    }
                }

                @Override
                public void failed(Throwable exc, ByteBuffer attachment) {
                    subscription.cancel();
                    future.completeExceptionally(exc);
                }
            });
        }

        @Override
        public void onError(Throwable t) {
            onErrorMethod.accept(t);
        }

        @Override
        public void onComplete() {
            log.trace(() -> "onComplete");
            // if write in progress, tell write to close on finish.
            synchronized (this) {
                if (writeInProgress) {
                    log.trace(() -> "writeInProgress = true, not closing");
                    closeOnLastWrite = true;
                } else {
                    log.trace(() -> "writeInProgress = false, closing");
                    close();
                }
            }
        }

        private void close() {
            try {
                if (fileChannel != null) {
                    invokeSafely(fileChannel::close);
                }
                log.trace(() -> "Completing File async transformer future future");
                future.complete(null);
            } catch (RuntimeException exception) {
                future.completeExceptionally(exception);
            }
        }

        @Override
        public String toString() {
            return getClass() + ":" + path.toString();
        }
    }
}