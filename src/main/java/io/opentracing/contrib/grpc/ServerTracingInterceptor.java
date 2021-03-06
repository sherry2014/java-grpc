/*
 * Copyright 2017-2019 The OpenTracing Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package io.opentracing.contrib.grpc;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import io.grpc.BindableService;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.ForwardingServerCall;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.Tracer;
import io.opentracing.log.Fields;
import io.opentracing.propagation.Format;
import io.opentracing.propagation.TextMapAdapter;
import io.opentracing.tag.Tags;
import io.opentracing.util.GlobalTracer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * An interceptor that applies tracing via OpenTracing to all requests to the server.
 */
public class ServerTracingInterceptor implements ServerInterceptor {

  private final Tracer tracer;
  private final OperationNameConstructor operationNameConstructor;
  private final boolean streaming;
  private final boolean verbose;
  private final Set<ServerRequestAttribute> tracedAttributes;
  private final ServerSpanDecorator serverSpanDecorator;
  private final ServerCloseDecorator serverCloseDecorator;

  /**
   * Instantiate interceptor using GlobalTracer to get tracer
   */
  public ServerTracingInterceptor() {
    this(GlobalTracer.get());
  }

  /**
   * @param tracer used to trace requests
   */
  public ServerTracingInterceptor(Tracer tracer) {
    this.tracer = tracer;
    this.operationNameConstructor = OperationNameConstructor.DEFAULT;
    this.streaming = false;
    this.verbose = false;
    this.tracedAttributes = new HashSet<>();
    this.serverSpanDecorator = null;
    this.serverCloseDecorator = null;
  }

  private ServerTracingInterceptor(
      Tracer tracer,
      OperationNameConstructor operationNameConstructor,
      boolean streaming,
      boolean verbose,
      Set<ServerRequestAttribute> tracedAttributes,
      ServerSpanDecorator serverSpanDecorator,
      ServerCloseDecorator serverCloseDecorator) {

    this.tracer = tracer;
    this.operationNameConstructor = operationNameConstructor;
    this.streaming = streaming;
    this.verbose = verbose;
    this.tracedAttributes = tracedAttributes;
    this.serverSpanDecorator = serverSpanDecorator;
    this.serverCloseDecorator = serverCloseDecorator;
  }

  /**
   * Add tracing to all requests made to this service.
   *
   * @param serviceDef of the service to intercept
   * @return the serviceDef with a tracing interceptor
   */
  public ServerServiceDefinition intercept(ServerServiceDefinition serviceDef) {
    return ServerInterceptors.intercept(serviceDef, this);
  }

  /**
   * Add tracing to all requests made to this service.
   *
   * @param bindableService to intercept
   * @return the serviceDef with a tracing interceptor
   */
  public ServerServiceDefinition intercept(BindableService bindableService) {
    return ServerInterceptors.intercept(bindableService, this);
  }

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call,
      Metadata headers,
      ServerCallHandler<ReqT, RespT> next) {

    final Set<String> headerKeys = headers.keys();
    Map<String, String> headerMap = new HashMap<>(headerKeys.size());
    for (String key : headerKeys) {
      if (!key.endsWith(Metadata.BINARY_HEADER_SUFFIX)) {
        String value = headers.get(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER));
        headerMap.put(key, value);
      }
    }

    final Span span = getSpanFromHeaders(headerMap,
        operationNameConstructor.constructOperationName(call.getMethodDescriptor()));

    try (Scope ignored = tracer.scopeManager().activate(span)) {

      if (serverSpanDecorator != null) {
        serverSpanDecorator.interceptCall(span, call, headers);
      }

      for (ServerRequestAttribute attr : this.tracedAttributes) {
        switch (attr) {
          case METHOD_TYPE:
            GrpcTags.GRPC_METHOD_TYPE.set(span, call.getMethodDescriptor());
            break;
          case METHOD_NAME:
            GrpcTags.GRPC_METHOD_NAME.set(span, call.getMethodDescriptor());
            break;
          case CALL_ATTRIBUTES:
            GrpcTags.GRPC_CALL_ATTRIBUTES.set(span, call.getAttributes());
            break;
          case HEADERS:
            GrpcTags.GRPC_HEADERS.set(span, headers);
            break;
          case PEER_ADDRESS:
            GrpcTags.PEER_ADDRESS.set(span, call.getAttributes());
            break;
        }
      }

      Context ctxWithSpan = Context.current()
          .withValue(OpenTracingContextKey.getKey(), span)
          .withValue(OpenTracingContextKey.getSpanContextKey(), span.context());

      final ServerCall<ReqT, RespT> decoratedCall = new ForwardingServerCall
          .SimpleForwardingServerCall<ReqT, RespT>(call) {

        @Override
        public void sendHeaders(Metadata headers) {
          if (verbose) {
            span.log(ImmutableMap.<String, Object>builder()
                .put(Fields.EVENT, GrpcFields.SERVER_CALL_SEND_HEADERS)
                .put(Fields.MESSAGE, "Server sent response headers")
                .put(GrpcFields.HEADERS, headers.toString())
                .build());
          }
          delegate().sendHeaders(headers);
        }

        @Override
        public void sendMessage(RespT message) {
          if (streaming || verbose) {
            span.log(ImmutableMap.<String, Object>builder()
                .put(Fields.EVENT, GrpcFields.SERVER_CALL_SEND_MESSAGE)
                .put(Fields.MESSAGE, "Server sent response message")
                .build());
          }
          delegate().sendMessage(message);
        }

        @Override
        public void close(Status status, Metadata trailers) {
          if (verbose) {
            span.log(ImmutableMap.<String, Object>builder()
                .put(Fields.EVENT, GrpcFields.SERVER_CALL_CLOSE)
                .put(Fields.MESSAGE, "Server call closed")
                .build());
            if (!status.isOk()) {
              GrpcFields.logServerCallError(span, status.getDescription(), status.getCause());
            }
          }
          GrpcTags.GRPC_STATUS.set(span, status);
          if (serverCloseDecorator != null) {
            serverCloseDecorator.close(span, status, trailers);
          }
          delegate().close(status, trailers);
        }
      };

      ServerCall.Listener<ReqT> listenerWithContext = Contexts
          .interceptCall(ctxWithSpan, decoratedCall, headers, next);

      return new ForwardingServerCallListener.SimpleForwardingServerCallListener<ReqT>(
          listenerWithContext) {

        @Override
        public void onMessage(ReqT message) {
          if (streaming || verbose) {
            span.log(ImmutableMap.<String, Object>builder()
                .put(Fields.EVENT, GrpcFields.SERVER_CALL_LISTENER_ON_MESSAGE)
                .put(Fields.MESSAGE, "Server received message")
                .build());
          }
          try (Scope ignored = tracer.scopeManager().activate(span)) {
            delegate().onMessage(message);
          }
        }

        @Override
        public void onHalfClose() {
          if (streaming || verbose) {
            span.log(ImmutableMap.<String, Object>builder()
                .put(Fields.EVENT, GrpcFields.SERVER_CALL_LISTENER_ON_HALF_CLOSE)
                .put(Fields.MESSAGE, "Server received all messages")
                .build());
          }
          try (Scope ignored = tracer.scopeManager().activate(span)) {
            delegate().onHalfClose();
          }

        }

        @Override
        public void onCancel() {
          if (verbose) {
            span.log(ImmutableMap.<String, Object>builder()
                .put(Fields.EVENT, GrpcFields.SERVER_CALL_LISTENER_ON_CANCEL)
                .put(Fields.MESSAGE, "Server call cancelled")
                .build());
          }
          GrpcTags.GRPC_STATUS.set(span, Status.CANCELLED);
          try (Scope ignored = tracer.scopeManager().activate(span)) {
            delegate().onCancel();
          } finally {
            span.finish();
          }
        }

        @Override
        public void onComplete() {
          if (verbose) {
            span.log(ImmutableMap.<String, Object>builder()
                .put(Fields.EVENT, GrpcFields.SERVER_CALL_LISTENER_ON_COMPLETE)
                .put(Fields.MESSAGE, "Server call completed")
                .build());
          }
          // Server span may complete with non-OK ServerCall.close(status).
          try (Scope ignored = tracer.scopeManager().activate(span)) {
            delegate().onComplete();
          } finally {
            span.finish();
          }
        }

        @Override
        public void onReady() {
          try (Scope ignored = tracer.scopeManager().activate(span)) {
            delegate().onReady();
          }
        }
      };
    }
  }

  @VisibleForTesting
  Span getSpanFromHeaders(Map<String, String> headers, String operationName) {
    Map<String, Object> fields = null;
    Tracer.SpanBuilder spanBuilder = tracer.buildSpan(operationName);
    try {
      SpanContext parentSpanCtx = tracer.extract(Format.Builtin.HTTP_HEADERS, new TextMapAdapter(headers));
      if (parentSpanCtx != null) {
        spanBuilder = spanBuilder.asChildOf(parentSpanCtx);
      }
    } catch (IllegalArgumentException iae) {
      spanBuilder = spanBuilder.withTag(Tags.ERROR, Boolean.TRUE);
      fields = ImmutableMap.<String, Object>builder()
          .put(Fields.EVENT, GrpcFields.ERROR)
          .put(Fields.ERROR_OBJECT, new RuntimeException("Parent span context extract failed", iae))
          .build();
    }
    Span span = spanBuilder
        .withTag(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_SERVER)
        .withTag(Tags.COMPONENT.getKey(), GrpcTags.COMPONENT_NAME)
        .start();
    if (fields != null) {
      span.log(fields);
    }
    return span;
  }

  /**
   * Builds the configuration of a ServerTracingInterceptor.
   */
  public static class Builder {

    private final Tracer tracer;
    private OperationNameConstructor operationNameConstructor;
    private boolean streaming;
    private boolean verbose;
    private Set<ServerRequestAttribute> tracedAttributes;
    private ServerSpanDecorator serverSpanDecorator;
    private ServerCloseDecorator serverCloseDecorator;

    /**
     * Creates a Builder using GlobalTracer to get tracer
     */
    public Builder() {
      this(GlobalTracer.get());
    }

    /**
     * @param tracer to use for this interceptor Creates a Builder with default configuration
     */
    public Builder(Tracer tracer) {
      this.tracer = tracer;
      this.operationNameConstructor = OperationNameConstructor.DEFAULT;
      this.streaming = false;
      this.verbose = false;
      this.tracedAttributes = new HashSet<>();
    }

    /**
     * @param operationNameConstructor for all spans created by this interceptor
     * @return this Builder with configured operation name
     */
    public Builder withOperationName(OperationNameConstructor operationNameConstructor) {
      this.operationNameConstructor = operationNameConstructor;
      return this;
    }

    /**
     * @param attributes to set as tags on server spans created by this interceptor
     * @return this Builder configured to trace request attributes
     */
    public Builder withTracedAttributes(ServerRequestAttribute... attributes) {
      this.tracedAttributes = new HashSet<>(Arrays.asList(attributes));
      return this;
    }

    /**
     * Logs streaming events to server spans.
     *
     * @return this Builder configured to log streaming events
     */
    public Builder withStreaming() {
      this.streaming = true;
      return this;
    }

    /**
     * Logs all request life-cycle events to server spans.
     *
     * @return this Builder configured to be verbose
     */
    public Builder withVerbosity() {
      this.verbose = true;
      return this;
    }

    /**
     * Decorates the server span with custom data.
     *
     * @param serverSpanDecorator used to decorate the server span
     * @return this Builder configured to decorate server span
     */
    public Builder withServerSpanDecorator(ServerSpanDecorator serverSpanDecorator) {
      this.serverSpanDecorator = serverSpanDecorator;
      return this;
    }

    /**
     * Decorates the server span with custom data when the gRPC call is closed.
     *
     * @param serverCloseDecorator used to decorate the server span
     * @return this Builder configured to decorate server span when the gRPC call is closed
     */
    public Builder withServerCloseDecorator(ServerCloseDecorator serverCloseDecorator) {
      this.serverCloseDecorator = serverCloseDecorator;
      return this;
    }

    /**
     * @return a ServerTracingInterceptor with this Builder's configuration
     */
    public ServerTracingInterceptor build() {
      return new ServerTracingInterceptor(this.tracer, this.operationNameConstructor,
          this.streaming, this.verbose, this.tracedAttributes, this.serverSpanDecorator,
          this.serverCloseDecorator);
    }
  }

  public enum ServerRequestAttribute {
    HEADERS,
    METHOD_TYPE,
    METHOD_NAME,
    CALL_ATTRIBUTES,
    PEER_ADDRESS
  }
}
