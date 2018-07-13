/*
 * Copyright 2018 Google LLC
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

package com.google.kgax.grpc

import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.ListeningExecutorService
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import com.google.longrunning.GetOperationRequest
import com.google.longrunning.Operation
import com.google.longrunning.OperationsGrpc
import com.google.protobuf.ByteString
import com.google.protobuf.MessageLite
import io.grpc.Status
import io.grpc.stub.AbstractStub
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** Block until this operation has completed and returned a result of the given [type]. */
fun <T : MessageLite> Operation.waitUntilDone(stub: AbstractStub<*>, type: Class<T>) =
        LongRunningCall.of(this, stub, type).get()

/** Block until this operation has completed and returned a result of the given [type]. */
fun <T : MessageLite> CallResult<Operation>.waitUntilDone(stub: AbstractStub<*>, type: Class<T>) =
        LongRunningCall.of(this.body, stub, type).get()

/** Resolves long running operations. */
class LongRunningCall<T : MessageLite>(private val stub: OperationsGrpc.OperationsFutureStub,
                                       private val future: ListenableFuture<Operation>,
                                       private val responseType: Class<T>,
                                       private val executor: ListeningExecutorService = LongRunningCall.executor
) {

    /** the underlying operation (null until the operation has completed) */
    var operation: Operation? = null
        private set

    /** If the operation is done */
    val isDone = future.isDone

    internal fun waitUntilDone(): CallResult<T> {
        operation = future.get()
        while (!operation!!.done) {
            try {
                operation = stub.getOperation(GetOperationRequest.newBuilder()
                        .setName(operation!!.name)
                        .build()).get()
            } catch (e: InterruptedException) {
                /** ignore and try again */
            }
        }
        // TODO: get actual metadata
        return CallResult(parseResult(operation!!, responseType), ResponseMetadata())
    }

    /** Block until the operation has been completed. */
    fun get() = asFuture().get()

    /** Add a [callback] that will be run on the provided [executor] when the CallResult is available */
    fun enqueue(executor: Executor, callback: (CallResult<T>) -> Unit) = asFuture().enqueue(executor, callback)

    /** Add a [callback] that will be run on the same thread as the caller */
    fun enqueue(callback: (CallResult<T>) -> Unit) = asFuture().enqueue(callback)

    /** Get a future that will resolve when the operation has been completed. */
    fun asFuture(): FutureCall<T> = executor.submit(Callable<CallResult<T>> { waitUntilDone() })

    companion object {
        /** The executor to use for resolving operations/ */
        var executor: ListeningExecutorService = MoreExecutors.listeningDecorator(
                Executors.newCachedThreadPool())

        fun <T : MessageLite> of(operation: Operation,
                                 stub: AbstractStub<*>,
                                 type: Class<T>
        ): LongRunningCall<T> {
            val s = OperationsGrpc.newFutureStub(stub.getChannel())
                    .withExecutor(executor)
                    .withCallCredentials(stub.getCallOptions().credentials)
            val future = SettableFuture.create<Operation>()
            future.set(operation)
            return LongRunningCall(s, future, type)
        }

        /** Parse the result of the [op] to the given [type] or throw an error */
        private fun <T : MessageLite> parseResult(op: Operation, type: Class<T>): T {
            if (op.error == null || op.error.code == Status.Code.OK.value()) {
                return type.getMethod("parseFrom",
                        ByteString::class.java).invoke(null, op.response.value) as T
            }

            throw RuntimeException("Operation completed with error: ${op.error.code}\n details: ${op.error.message}")
        }

    }

}