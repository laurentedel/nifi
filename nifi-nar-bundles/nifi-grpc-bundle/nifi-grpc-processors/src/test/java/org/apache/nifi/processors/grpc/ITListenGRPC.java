/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.processors.grpc;

import com.google.common.collect.Sets;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import org.apache.nifi.flowfile.attributes.CoreAttributes;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSessionFactory;
import org.apache.nifi.util.MockFlowFile;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.junit.Test;

import java.util.List;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.StringContains.containsString;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

public class ITListenGRPC {
    private static final String HOST = "localhost";
    private static final String SOURCE_SYSTEM_UUID = "FAKE_UUID";

    @Test
    public void testSuccessfulRoundTrip() throws Exception {
        final int randPort = TestGRPCClient.randomPort();
        final ManagedChannel channel = TestGRPCClient.buildChannel(HOST, randPort);
        final FlowFileServiceGrpc.FlowFileServiceBlockingStub stub = FlowFileServiceGrpc.newBlockingStub(channel);

        final ListenGRPC listenGRPC = new ListenGRPC();
        final TestRunner runner = TestRunners.newTestRunner(listenGRPC);
        runner.setProperty(ListenGRPC.PROP_SERVICE_PORT, String.valueOf(randPort));

        final ProcessContext processContext = runner.getProcessContext();
        final ProcessSessionFactory processSessionFactory = runner.getProcessSessionFactory();

        try {
            // start the server. The order of the following statements shouldn't matter, because the
            // startServer() method waits for a processSessionFactory to be available to it.
            listenGRPC.startServer(processContext);
            listenGRPC.onTrigger(processContext, processSessionFactory);

            final FlowFileRequest ingestFile = FlowFileRequest.newBuilder()
                    .putAttributes("FOO", "BAR")
                    .putAttributes(CoreAttributes.UUID.key(), SOURCE_SYSTEM_UUID)
                    .setContent(ByteString.copyFrom("content".getBytes()))
                    .build();
            final FlowFileReply reply = stub.send(ingestFile);
            assertThat(reply.getResponseCode(), equalTo(FlowFileReply.ResponseCode.SUCCESS));
            assertThat(reply.getBody(), equalTo("FlowFile successfully received."));

            runner.assertTransferCount(ListenGRPC.REL_SUCCESS, 1);
            final List<MockFlowFile> successFiles = runner.getFlowFilesForRelationship(ListenGRPC.REL_SUCCESS);
            assertThat(successFiles.size(), equalTo(1));
            final MockFlowFile mockFlowFile = successFiles.get(0);
            assertThat(mockFlowFile.getAttribute("FOO"), equalTo("BAR"));
            assertThat(mockFlowFile.getAttribute(ListenGRPC.REMOTE_HOST), equalTo("127.0.0.1"));
            assertThat(mockFlowFile.getAttribute(ListenGRPC.REMOTE_USER_DN), equalTo(FlowFileIngestServiceInterceptor.DEFAULT_FOUND_SUBJECT));

        } finally {
            // stop the server
            listenGRPC.stopServer(processContext);
            channel.shutdown();
        }
    }

    @Test
    public void testOutOfSpaceRoundTrip() throws Exception {
        final int randPort = TestGRPCClient.randomPort();
        final ManagedChannel channel = TestGRPCClient.buildChannel(HOST, randPort);
        final FlowFileServiceGrpc.FlowFileServiceBlockingStub stub = FlowFileServiceGrpc.newBlockingStub(channel);

        final ListenGRPC listenGRPC = new ListenGRPC();
        final TestRunner runner = TestRunners.newTestRunner(listenGRPC);
        runner.setProperty(ListenGRPC.PROP_SERVICE_PORT, String.valueOf(randPort));

        final ProcessContext processContext = spy(runner.getProcessContext());
        // force the context to return that space isn't available, prompting an error message to be returned.
        when(processContext.getAvailableRelationships()).thenReturn(Sets.newHashSet());
        final ProcessSessionFactory processSessionFactory = runner.getProcessSessionFactory();

        try {
            // start the server. The order of the following statements shouldn't matter, because the
            // startServer() method waits for a processSessionFactory to be available to it.
            listenGRPC.startServer(processContext);
            listenGRPC.onTrigger(processContext, processSessionFactory);

            final FlowFileRequest ingestFile = FlowFileRequest.newBuilder()
                    .putAttributes("FOO", "BAR")
                    .setContent(ByteString.copyFrom("content".getBytes()))
                    .build();
            final FlowFileReply reply = stub.send(ingestFile);
            assertThat(reply.getResponseCode(), equalTo(FlowFileReply.ResponseCode.ERROR));
            assertThat(reply.getBody(), containsString("but no space available; Indicating Service Unavailable"));

            runner.assertTransferCount(ListenGRPC.REL_SUCCESS, 0);
        } finally {
            // stop the server
            listenGRPC.stopServer(processContext);
            channel.shutdown();
        }
    }

    @Test(expected = io.grpc.StatusRuntimeException.class)
    public void testExceedMaxMessageSize() throws Exception {
        final int randPort = TestGRPCClient.randomPort();
        final ManagedChannel channel = TestGRPCClient.buildChannel(HOST, randPort);
        final FlowFileServiceGrpc.FlowFileServiceBlockingStub stub = FlowFileServiceGrpc.newBlockingStub(channel);

        final ListenGRPC listenGRPC = new ListenGRPC();
        final TestRunner runner = TestRunners.newTestRunner(listenGRPC);
        runner.setProperty(ListenGRPC.PROP_SERVICE_PORT, String.valueOf(randPort));
        // set max message size to 1 byte to force exception to be thrown.
        runner.setProperty(ListenGRPC.PROP_MAX_MESSAGE_SIZE, "1B");

        final ProcessContext processContext = runner.getProcessContext();
        final ProcessSessionFactory processSessionFactory = runner.getProcessSessionFactory();

        try {
            // start the server. The order of the following statements shouldn't matter, because the
            // startServer() method waits for a processSessionFactory to be available to it.
            listenGRPC.startServer(processContext);
            listenGRPC.onTrigger(processContext, processSessionFactory);

            final FlowFileRequest ingestFile = FlowFileRequest.newBuilder()
                    .putAttributes("FOO", "BAR")
                    .putAttributes(CoreAttributes.UUID.key(), SOURCE_SYSTEM_UUID)
                    .setContent(ByteString.copyFrom("content".getBytes()))
                    .build();
            // this should throw a runtime exception
            final FlowFileReply reply = stub.send(ingestFile);
            assertThat(reply.getResponseCode(), equalTo(FlowFileReply.ResponseCode.SUCCESS));
            assertThat(reply.getBody(), equalTo("FlowFile successfully received."));

            runner.assertTransferCount(ListenGRPC.REL_SUCCESS, 1);
            final List<MockFlowFile> successFiles = runner.getFlowFilesForRelationship(ListenGRPC.REL_SUCCESS);
            assertThat(successFiles.size(), equalTo(1));
            final MockFlowFile mockFlowFile = successFiles.get(0);
            assertThat(mockFlowFile.getAttribute("FOO"), equalTo("BAR"));
            assertThat(mockFlowFile.getAttribute(ListenGRPC.REMOTE_HOST), equalTo("127.0.0.1"));
            assertThat(mockFlowFile.getAttribute(ListenGRPC.REMOTE_USER_DN), equalTo(FlowFileIngestServiceInterceptor.DEFAULT_FOUND_SUBJECT));

        } finally {
            // stop the server
            listenGRPC.stopServer(processContext);
            channel.shutdown();
        }
    }
}
