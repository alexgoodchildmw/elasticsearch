/*
 * Licensed to ElasticSearch and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. ElasticSearch licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
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

package org.elasticsearch.cluster.action.index;

import org.elasticsearch.ElasticSearchException;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.VoidStreamable;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.*;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 *
 */
public class NodeMappingCreatedAction extends AbstractComponent {

    private final ThreadPool threadPool;

    private final TransportService transportService;

    private final ClusterService clusterService;

    private final List<Listener> listeners = new CopyOnWriteArrayList<Listener>();

    @Inject
    public NodeMappingCreatedAction(Settings settings, ThreadPool threadPool, TransportService transportService, ClusterService clusterService) {
        super(settings);
        this.threadPool = threadPool;
        this.transportService = transportService;
        this.clusterService = clusterService;
        transportService.registerHandler(NodeMappingCreatedTransportHandler.ACTION, new NodeMappingCreatedTransportHandler());
    }

    public void add(final Listener listener, TimeValue timeout) {
        listeners.add(listener);
        threadPool.schedule(timeout, ThreadPool.Names.GENERIC, new Runnable() {
            @Override
            public void run() {
                boolean removed = listeners.remove(listener);
                if (removed) {
                    listener.onTimeout();
                }
            }
        });
    }

    public void remove(Listener listener) {
        listeners.remove(listener);
    }

    public void nodeMappingCreated(final NodeMappingCreatedResponse response) throws ElasticSearchException {
        DiscoveryNodes nodes = clusterService.state().nodes();
        if (nodes.localNodeMaster()) {
            threadPool.generic().execute(new Runnable() {
                @Override
                public void run() {
                    innerNodeIndexCreated(response);
                }
            });
        } else {
            transportService.sendRequest(clusterService.state().nodes().masterNode(),
                    NodeMappingCreatedTransportHandler.ACTION, response, VoidTransportResponseHandler.INSTANCE_SAME);
        }
    }

    private void innerNodeIndexCreated(NodeMappingCreatedResponse response) {
        for (Listener listener : listeners) {
            listener.onNodeMappingCreated(response);
        }
    }


    public static interface Listener {
        void onNodeMappingCreated(NodeMappingCreatedResponse response);

        void onTimeout();
    }

    private class NodeMappingCreatedTransportHandler extends BaseTransportRequestHandler<NodeMappingCreatedResponse> {

        static final String ACTION = "cluster/nodeMappingCreated";

        @Override
        public NodeMappingCreatedResponse newInstance() {
            return new NodeMappingCreatedResponse();
        }

        @Override
        public void messageReceived(NodeMappingCreatedResponse response, TransportChannel channel) throws Exception {
            innerNodeIndexCreated(response);
            channel.sendResponse(VoidStreamable.INSTANCE);
        }

        @Override
        public String executor() {
            return ThreadPool.Names.SAME;
        }
    }

    public static class NodeMappingCreatedResponse extends TransportRequest {

        private String index;
        private String type;
        private String nodeId;

        private NodeMappingCreatedResponse() {
        }

        public NodeMappingCreatedResponse(String index, String type, String nodeId) {
            this.index = index;
            this.type = type;
            this.nodeId = nodeId;
        }

        public String index() {
            return index;
        }

        public String type() {
            return type;
        }

        public String nodeId() {
            return nodeId;
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            out.writeString(index);
            out.writeString(type);
            out.writeString(nodeId);
        }

        @Override
        public void readFrom(StreamInput in) throws IOException {
            super.readFrom(in);
            index = in.readString();
            type = in.readString();
            nodeId = in.readString();
        }
    }
}
