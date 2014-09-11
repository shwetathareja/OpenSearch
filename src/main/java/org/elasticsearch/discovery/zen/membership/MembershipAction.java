/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.discovery.zen.membership;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.Version;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.discovery.zen.DiscoveryNodesProvider;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.*;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 *
 */
public class MembershipAction extends AbstractComponent {

    public static final String DISCOVERY_JOIN_ACTION_NAME = "internal:discovery/zen/join";
    public static final String DISCOVERY_JOIN_VALIDATE_ACTION_NAME = "internal:discovery/zen/join/validate";
    public static final String DISCOVERY_LEAVE_ACTION_NAME = "internal:discovery/zen/leave";

    public static interface JoinCallback {
        void onSuccess();

        void onFailure(Throwable t);
    }

    public static interface MembershipListener {
        void onJoin(DiscoveryNode node, JoinCallback callback);

        void onLeave(DiscoveryNode node);
    }

    private final TransportService transportService;

    private final DiscoveryNodesProvider nodesProvider;

    private final MembershipListener listener;

    private final ClusterService clusterService;

    public MembershipAction(Settings settings, ClusterService clusterService, TransportService transportService, DiscoveryNodesProvider nodesProvider, MembershipListener listener) {
        super(settings);
        this.transportService = transportService;
        this.nodesProvider = nodesProvider;
        this.listener = listener;
        this.clusterService = clusterService;

        transportService.registerHandler(DISCOVERY_JOIN_ACTION_NAME, new JoinRequestRequestHandler());
        transportService.registerHandler(DISCOVERY_JOIN_VALIDATE_ACTION_NAME, new ValidateJoinRequestRequestHandler());
        transportService.registerHandler(DISCOVERY_LEAVE_ACTION_NAME, new LeaveRequestRequestHandler());
    }

    public void close() {
        transportService.removeHandler(DISCOVERY_JOIN_ACTION_NAME);
        transportService.removeHandler(DISCOVERY_JOIN_VALIDATE_ACTION_NAME);
        transportService.removeHandler(DISCOVERY_LEAVE_ACTION_NAME);
    }

    public void sendLeaveRequest(DiscoveryNode masterNode, DiscoveryNode node) {
        transportService.sendRequest(node, DISCOVERY_LEAVE_ACTION_NAME, new LeaveRequest(masterNode), EmptyTransportResponseHandler.INSTANCE_SAME);
    }

    public void sendLeaveRequestBlocking(DiscoveryNode masterNode, DiscoveryNode node, TimeValue timeout) throws ElasticsearchException {
        transportService.submitRequest(masterNode, DISCOVERY_LEAVE_ACTION_NAME, new LeaveRequest(node), EmptyTransportResponseHandler.INSTANCE_SAME).txGet(timeout.millis(), TimeUnit.MILLISECONDS);
    }

    public void sendJoinRequest(DiscoveryNode masterNode, DiscoveryNode node) {
        transportService.sendRequest(masterNode, DISCOVERY_JOIN_ACTION_NAME, new JoinRequest(node), EmptyTransportResponseHandler.INSTANCE_SAME);
    }

    public void sendJoinRequestBlocking(DiscoveryNode masterNode, DiscoveryNode node, TimeValue timeout) throws ElasticsearchException {
        transportService.submitRequest(masterNode, DISCOVERY_JOIN_ACTION_NAME, new JoinRequest(node), EmptyTransportResponseHandler.INSTANCE_SAME)
                .txGet(timeout.millis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Validates the join request, throwing a failure if it failed.
     */
    public void sendValidateJoinRequestBlocking(DiscoveryNode node, TimeValue timeout) throws ElasticsearchException {
        transportService.submitRequest(node, DISCOVERY_JOIN_VALIDATE_ACTION_NAME, new ValidateJoinRequest(), EmptyTransportResponseHandler.INSTANCE_SAME)
                .txGet(timeout.millis(), TimeUnit.MILLISECONDS);
    }

    static class JoinRequest extends TransportRequest {

        DiscoveryNode node;

        // here for backward compatibility. nodes with a version lower than 1.4.0 send this flag
        boolean withClusterState = false;

        private JoinRequest() {
        }

        private JoinRequest(DiscoveryNode node) {
            this.node = node;
        }

        @Override
        public void readFrom(StreamInput in) throws IOException {
            super.readFrom(in);
            node = DiscoveryNode.readNode(in);
            if (in.getVersion().before(Version.V_1_4_0_Beta)) {
                withClusterState = in.readBoolean();
            }
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            node.writeTo(out);
            if (out.getVersion().before(Version.V_1_4_0_Beta)) {
                // old with cluster state flag
                out.writeBoolean(false);
            }
        }
    }


    // used to reply to nodes from a version older than 1.4.0 which may expect this
    @Deprecated
    class JoinResponse extends TransportResponse {

        ClusterState clusterState;

        JoinResponse() {
        }

        JoinResponse(ClusterState clusterState) {
            this.clusterState = clusterState;
        }

        @Override
        public void readFrom(StreamInput in) throws IOException {
            super.readFrom(in);
            // we don't care about cluster name. This cluster state is never used.
            clusterState = ClusterState.Builder.readFrom(in, nodesProvider.nodes().localNode(), null);
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            ClusterState.Builder.writeTo(clusterState, out);
        }
    }

    private class JoinRequestRequestHandler extends BaseTransportRequestHandler<JoinRequest> {

        @Override
        public JoinRequest newInstance() {
            return new JoinRequest();
        }

        @Override
        public void messageReceived(final JoinRequest request, final TransportChannel channel) throws Exception {
            listener.onJoin(request.node, new JoinCallback() {
                @Override
                public void onSuccess() {
                    try {
                        // nodes from a version older than 1.4.0 may ask for this
                        if (request.withClusterState) {
                            channel.sendResponse(new JoinResponse(clusterService.state()));
                        } else {
                            channel.sendResponse(TransportResponse.Empty.INSTANCE);
                        }
                    } catch (Throwable t) {
                        onFailure(t);
                    }
                }

                @Override
                public void onFailure(Throwable t) {
                    try {
                        channel.sendResponse(t);
                    } catch (Throwable e) {
                        logger.warn("failed to send back failure on join request", e);
                    }
                }
            });
        }

        @Override
        public String executor() {
            return ThreadPool.Names.GENERIC;
        }
    }

    class ValidateJoinRequest extends TransportRequest {

        ValidateJoinRequest() {
        }

        @Override
        public void readFrom(StreamInput in) throws IOException {
            super.readFrom(in);
            if (in.getVersion().before(Version.V_1_4_0_Beta)) {
                // cluster name doesn't matter...
                ClusterState.Builder.readFrom(in, nodesProvider.nodes().localNode(), null);
            }
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            if (out.getVersion().before(Version.V_1_4_0_Beta)) {
                ClusterState.Builder.writeTo(clusterService.state(), out);
            }
        }
    }

    private class ValidateJoinRequestRequestHandler extends BaseTransportRequestHandler<ValidateJoinRequest> {

        @Override
        public ValidateJoinRequest newInstance() {
            return new ValidateJoinRequest();
        }

        @Override
        public void messageReceived(ValidateJoinRequest request, TransportChannel channel) throws Exception {
            // for now, the mere fact that we can serialize the cluster state acts as validation....
            channel.sendResponse(TransportResponse.Empty.INSTANCE);
        }

        @Override
        public String executor() {
            return ThreadPool.Names.GENERIC;
        }
    }

    static class LeaveRequest extends TransportRequest {

        private DiscoveryNode node;

        private LeaveRequest() {
        }

        private LeaveRequest(DiscoveryNode node) {
            this.node = node;
        }

        @Override
        public void readFrom(StreamInput in) throws IOException {
            super.readFrom(in);
            node = DiscoveryNode.readNode(in);
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            node.writeTo(out);
        }
    }

    private class LeaveRequestRequestHandler extends BaseTransportRequestHandler<LeaveRequest> {

        @Override
        public LeaveRequest newInstance() {
            return new LeaveRequest();
        }

        @Override
        public void messageReceived(LeaveRequest request, TransportChannel channel) throws Exception {
            listener.onLeave(request.node);
            channel.sendResponse(TransportResponse.Empty.INSTANCE);
        }

        @Override
        public String executor() {
            return ThreadPool.Names.GENERIC;
        }
    }
}
