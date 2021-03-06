/*
 * Copyright 2020 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.spannerclient;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.protobuf.Empty;
import com.google.spanner.v1.*;
import io.grpc.InternalWithLogId;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import io.grpc.auth.MoreCallCredentials;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLInvalidAuthorizationSpecException;
import java.sql.SQLNonTransientException;
import java.sql.SQLRecoverableException;
import java.sql.SQLSyntaxErrorException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GrpcClient {
  private static final Logger log = LoggerFactory.getLogger(GrpcClient.class);
  private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

  private final ManagedChannel channel;
  private final GoogleCredentials credentials;
  private final String channelId;
  private final SpannerGrpc.SpannerStub asyncStub;
  private final SpannerGrpc.SpannerFutureStub futureStub;
  private final Duration timeout;

  GrpcClient(ManagedChannel channel, GoogleCredentials credentials) {
    Preconditions.checkNotNull(channel);
    Preconditions.checkNotNull(credentials);

    this.channel = channel;
    this.credentials = credentials;

    channelId = toChannelId(channel);
    asyncStub =
        SpannerGrpc.newStub(channel).withCallCredentials(MoreCallCredentials.from(credentials));
    futureStub =
        SpannerGrpc.newFutureStub(channel)
            .withCallCredentials(MoreCallCredentials.from(credentials));
    timeout = DEFAULT_TIMEOUT;
  }

  private String toChannelId(ManagedChannel channel) {
    return channel instanceof InternalWithLogId
        ? ((InternalWithLogId) channel).getLogId().toString()
        : channel.toString();
  }

  public void close() throws IOException {
    try {
      if (!channel.shutdown().awaitTermination(timeout.getSeconds(), TimeUnit.SECONDS)) {
        // The channel failed to shut down cleanly within the specified window
        // Now we try hard shutdown
        channel.shutdownNow();
      }
    } catch (InterruptedException exc) {
      Thread.currentThread().interrupt();
    }
  }

  ListenableFuture<Session> createSession(Context ctx, CreateSessionRequest request) {
    Preconditions.checkNotNull(ctx);
    Preconditions.checkNotNull(request);

    return Futures.catchingAsync(
        getFutureStub(ctx, credentials).createSession(request),
        Exception.class,
        new ExceptionConverter<Session>(),
        MoreExecutors.directExecutor());
  }

  ListenableFuture<BatchCreateSessionsResponse> batchCreateSession(
      Context ctx, BatchCreateSessionsRequest request) {
    Preconditions.checkNotNull(ctx);
    Preconditions.checkNotNull(request);

    return Futures.catchingAsync(
        getFutureStub(ctx, credentials).batchCreateSessions(request),
        Exception.class,
        new ExceptionConverter<BatchCreateSessionsResponse>(),
        MoreExecutors.directExecutor());
  }

  ListenableFuture<Session> getSession(Context ctx, GetSessionRequest request) {
    Preconditions.checkNotNull(ctx);
    Preconditions.checkNotNull(request);

    return Futures.catchingAsync(
        getFutureStub(ctx, credentials).getSession(request),
        Exception.class,
        new ExceptionConverter<Session>(),
        MoreExecutors.directExecutor());
  }

  ListenableFuture<Empty> deleteSession(Context ctx, DeleteSessionRequest request) {
    Preconditions.checkNotNull(ctx);
    Preconditions.checkNotNull(request);

    return Futures.catchingAsync(
        getFutureStub(ctx, credentials).deleteSession(request),
        Exception.class,
        new ExceptionConverter<Empty>(),
        MoreExecutors.directExecutor());
  }

  ListenableFuture<ResultSet> executeSql(Context ctx, ExecuteSqlRequest request) {
    Preconditions.checkNotNull(ctx);
    Preconditions.checkNotNull(request);

    return Futures.catchingAsync(
        getFutureStub(ctx, credentials).executeSql(request),
        Exception.class,
        new ExceptionConverter<ResultSet>(),
        MoreExecutors.directExecutor());
  }

  void executeStreamingSql(
      Context ctx, ExecuteSqlRequest request, StreamObserver<PartialResultSet> responseObserver) {
    Preconditions.checkNotNull(ctx);
    Preconditions.checkNotNull(request);

    log.info("Executing streaming request '{}'", request);
    getAsyncStub(ctx, credentials).executeStreamingSql(request, responseObserver);
  }

  private SpannerGrpc.SpannerStub getAsyncStub(Context ctx, GoogleCredentials credentials) {
    Duration timeout = ctx.getTimeout();
    if (timeout == null) {
      return asyncStub;
    }

    return asyncStub
        .withCallCredentials(MoreCallCredentials.from(credentials))
        .withDeadlineAfter(
            TimeUnit.MILLISECONDS.convert(timeout.toNanos(), TimeUnit.NANOSECONDS),
            TimeUnit.MILLISECONDS);
  }

  private SpannerGrpc.SpannerFutureStub getFutureStub(Context ctx, GoogleCredentials credentials) {
    Duration timeout = ctx.getTimeout();
    if (timeout == null) {
      return futureStub;
    }

    return futureStub
        .withCallCredentials(MoreCallCredentials.from(credentials))
        .withDeadlineAfter(
            TimeUnit.MILLISECONDS.convert(timeout.toNanos(), TimeUnit.NANOSECONDS),
            TimeUnit.MILLISECONDS);
  }

  /** Converts an exception from the gRPC framework into the appropriate {@link SQLException}. */
  private static SQLException convertGrpcError(Throwable exc) {
    if (exc instanceof StatusRuntimeException) {
      StatusRuntimeException sre = (StatusRuntimeException) exc;

      // int errno = getErrno(sre.getMessage());
      // String sqlState = getSQLState(sre.getMessage());

      int errno = 0;
      String sqlState = "";

      switch (sre.getStatus().getCode()) {
        case INVALID_ARGUMENT:
          return new SQLSyntaxErrorException(sre.toString(), sqlState, errno, sre);
        case DEADLINE_EXCEEDED:
          return new SQLTimeoutException(sre.toString(), sqlState, errno, sre);
        case ALREADY_EXISTS:
          return new SQLIntegrityConstraintViolationException(sre.toString(), sqlState, errno, sre);
        case UNAUTHENTICATED:
          return new SQLInvalidAuthorizationSpecException(sre.toString(), sqlState, errno, sre);
        case UNAVAILABLE:
          return new SQLTransientException(sre.toString(), sqlState, errno, sre);
        case ABORTED:
          return new SQLRecoverableException(sre.toString(), sqlState, errno, sre);
        default: // Covers e.g. UNKNOWN.
          String advice = "";
          if (exc.getCause() instanceof java.nio.channels.ClosedChannelException) {
            advice =
                "Failed to connect to Spanner. Make sure that you have the proper service account and you are using "
                    + "the correct project. Details: ";
          }
          return new SQLNonTransientException(
              "gRPC StatusRuntimeException: " + advice + exc.toString(), sqlState, errno, exc);
      }
    }
    return new SQLNonTransientException("gRPC error: " + exc.toString(), exc);
  }

  static class ExceptionConverter<V> implements AsyncFunction<Exception, V> {

    @Override
    public ListenableFuture<V> apply(Exception exc) throws Exception {
      throw convertGrpcError(exc);
    }
  }

  @Override
  public String toString() {
    return String.format(
        "[GrpcClient-%s channel=%s]", Integer.toHexString(this.hashCode()), channelId);
  }

  private static Duration getContextTimeoutOrDefault(Context context) {
    if (context.getTimeout() == null || context.getTimeout().getSeconds() < 0) {
      return DEFAULT_TIMEOUT;
    }

    return context.getTimeout();
  }
}
