/*
 * Copyright 2016, Google Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *     * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *     * Neither the name of Google Inc. nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.google.api.gax.grpc;

import com.google.api.gax.bundling.ThresholdBundleReceiver;
import com.google.api.gax.core.ApiFuture;
import com.google.api.gax.core.ApiFutureCallback;
import com.google.api.gax.core.ApiFutures;
import com.google.common.base.Preconditions;
import java.util.List;

/**
 * A bundle receiver which uses a provided bundling descriptor to merge the items from the bundle
 * into a single request, invoke the callable from the bundling context to issue the request, split
 * the bundle response into the components matching each incoming request, and finally send the
 * result back to the listener for each request.
 *
 * BundleExecutor methods validateBundle and processBundle use the thread-safe guarantee of
 * BundlingDescriptor to achieve thread safety.
 *
 * <p>
 * Package-private for internal use.
 */
class BundleExecutor<RequestT, ResponseT>
    implements ThresholdBundleReceiver<Bundle<RequestT, ResponseT>> {

  private final BundlingDescriptor<RequestT, ResponseT> bundlingDescriptor;
  private final String partitionKey;

  public BundleExecutor(
      BundlingDescriptor<RequestT, ResponseT> bundlingDescriptor, String partitionKey) {
    this.bundlingDescriptor = Preconditions.checkNotNull(bundlingDescriptor);
    this.partitionKey = Preconditions.checkNotNull(partitionKey);
  }

  @Override
  public void validateBundle(Bundle<RequestT, ResponseT> item) {
    String itemPartitionKey = bundlingDescriptor.getBundlePartitionKey(item.getRequest());
    if (!itemPartitionKey.equals(partitionKey)) {
      String requestClassName = item.getRequest().getClass().getSimpleName();
      throw new IllegalArgumentException(
          String.format(
              "For type %s, invalid partition key: %s, should be: %s",
              requestClassName,
              itemPartitionKey,
              partitionKey));
    }
  }

  @Override
  public ApiFuture<ResponseT> processBundle(Bundle<RequestT, ResponseT> bundle) {
    UnaryCallable<RequestT, ResponseT> callable = bundle.getCallable();
    RequestT request = bundle.getRequest();
    final List<BundledRequestIssuer<ResponseT>> requestIssuerList = bundle.getRequestIssuerList();
    ApiFuture<ResponseT> future = callable.futureCall(request);
    ApiFutures.addCallback(
        future,
        new ApiFutureCallback<ResponseT>() {
          @Override
          public void onSuccess(ResponseT result) {
            bundlingDescriptor.splitResponse(result, requestIssuerList);
            for (BundledRequestIssuer<ResponseT> requestIssuer : requestIssuerList) {
              requestIssuer.sendResult();
            }
          }

          @Override
          public void onFailure(Throwable t) {
            bundlingDescriptor.splitException(t, requestIssuerList);
            for (BundledRequestIssuer<ResponseT> requestIssuer : requestIssuerList) {
              requestIssuer.sendResult();
            }
          }
        });
    return future;
  }
}