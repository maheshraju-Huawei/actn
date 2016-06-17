/*
 * Copyright 2016-present Open Networking Laboratory
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.onosproject.pceweb;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableSet;

import org.onlab.osgi.ServiceDirectory;
import org.onlab.packet.IpAddress;
import org.onlab.util.DataRateUnit;
import org.onosproject.net.Device;
import org.onosproject.net.DeviceId;
import org.onosproject.net.ElementId;
import org.onosproject.net.HostId;
import org.onosproject.net.Link;
import org.onosproject.net.Path;
import org.onosproject.net.topology.TopologyEvent;
import org.onosproject.net.topology.TopologyListener;
import org.onosproject.net.topology.TopologyService;
import org.onosproject.ui.RequestHandler;
import org.onosproject.ui.UiConnection;
import org.onosproject.ui.UiMessageHandler;
import org.onosproject.ui.topo.DeviceHighlight;
import org.onosproject.ui.topo.Highlights;
import org.onosproject.ui.topo.NodeBadge;
import org.onosproject.ui.topo.TopoJson;
import org.onosproject.net.device.DeviceService;
import org.onosproject.net.intent.Constraint;
import org.onosproject.pce.pceservice.LspType;
import org.onosproject.pce.pceservice.api.PceService;
import org.onosproject.pce.pceservice.constraint.CostConstraint;
import org.onosproject.net.intent.constraint.BandwidthConstraint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.onosproject.incubator.net.tunnel.IpTunnelEndPoint;
import org.onosproject.incubator.net.tunnel.Tunnel;
import org.onosproject.incubator.net.tunnel.TunnelEndPoint;
import org.onosproject.incubator.net.tunnel.TunnelEvent;
import org.onosproject.incubator.net.tunnel.TunnelId;
import org.onosproject.incubator.net.tunnel.TunnelListener;
import org.onosproject.incubator.net.tunnel.TunnelService;
import static org.onosproject.incubator.net.tunnel.Tunnel.Type.MPLS;

import com.fasterxml.jackson.databind.node.ArrayNode;

import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

/**
 * ONOS UI PCE WEB Topology-Overlay message handler.
 */
public class PceWebTopovMessageHandler extends UiMessageHandler {

    private static final String PCEWEB_CLEAR = "pceTopovClear";
    private static final String PCEWEB_SET_SRC = "pceTopovSetSrc";
    private static final String PCEWEB_SET_DST = "pceTopovSetDst";
    private static final String PCEWEB_SET_PATH = "pceTopovSetMode";
    private static final String PCEWEB_UPDATE_PATH_QUERY = "pceTopovUpdateQuery";
    private static final String PCEWEB_UPDATE_PATH = "pceTopovUpdate";
    private static final String PCEWEB_REMOVE_PATH_QUERY = "pceTopovRemQuery";
    private static final String PCEWEB_REMOVE_PATH = "pceTopovRem";
    private static final String PCEWEB_QUERY_TUNNELS = "pceTopovTunnelDisplay";
    private static final String PCEWEB_SHOW_TUNNEL = "pceTopovShowTunnels";
    private static final String PCEWEB_SHOW_TUNNEL_REMOVE = "pceTopovShowTunnelsRem";
    private static final String ID = "id";
    private static final String TYPE = "type";
    private static final String ROUTER = "router";
    private static final String DST = "Egress";
    private static final String SRC = "Ingress";
    private static final String BANDWIDTH = "bw";
    private static final String BANDWIDTHTYPE = "bwtype";
    private static final String COSTTYPE = "ctype";
    private static final String LSPTYPE = "lsptype";
    private static final String SRCID = "srid";
    private static final String DSTID = "dsid";
    private static final String TUNNEL_ID = "tunnelid";
    private static final String TUNNEL_NAME = "tunnelname";
    private static final String COST_TYPE_IGP = "igp";
    private static final String COST_TYPE_TE = "te";
    private static final String BANDWIDTH_TYPE_KBPS = "kbps";
    private static final String BUFFER_ARRAY = "a";
    private static final String BANDWIDTH_BPS = "BPS";
    private static final String LSP_TYPE_CR = "cr";
    private static final String LSP_TYPE_SRBE = "srbe";
    private static final String LSP_TYPE_SRTE = "srte";
    private static final String STRING_NULL = "null";
    // Delay for showHighlights event processing on GUI client side to
    // account for addLink animation.
    private static final int DELAY_MS = 1100;
    private static final double BANDWIDTH_KBPS = 1_000;
    private static final double BANDWIDTH_MBPS = 1_000_000;

    private Set<Link> allPathLinks;
    private int highlightDelay;
    private ElementId src, dst;
    private List<Path> paths = new LinkedList<>();
    private int pathIndex;

    private final Logger log = LoggerFactory.getLogger(getClass());
    private final TopologyListener topologyListener = new InternalTopologyListener();
    private final TunnelListener tunnelListener = new InnerPceWebTunnelListener();

    protected TopologyService topologyService;
    protected TunnelService tunnelService;
    protected PceService pceService;
    protected DeviceService deviceService;
    @Override
    public void init(UiConnection connection, ServiceDirectory directory) {

        super.init(connection, directory);
        topologyService = directory.get(TopologyService.class);
        tunnelService = directory.get(TunnelService.class);
        pceService = directory.get(PceService.class);
        deviceService = directory.get(DeviceService.class);
        topologyService.addListener(topologyListener);
        tunnelService.addListener(tunnelListener);
    }

    @Override
    protected Collection<RequestHandler> createRequestHandlers() {
        return ImmutableSet.of(
                new ClearHandler(),
                new SetPathHandler(),
                new UpdatePathQueryHandler(),
                new UpdatePathHandler(),
                new RemovePathQueryHandler(),
                new RemovePathHandler(),
                new ShowTunnelHandler());
    }

    @Override
    public void destroy() {
        topologyService.removeListener(topologyListener);
        tunnelService.removeListener(tunnelListener);
        super.destroy();
    }

    // Handler classes
    /**
     * Handles the 'clear' event received from the client.
     */
    private final class ClearHandler extends RequestHandler {

        public ClearHandler() {
            super(PCEWEB_CLEAR);
        }

        @Override
        public void process(long sid, ObjectNode payload) {
            src = null;
            dst = null;
            sendMessage(TopoJson.highlightsMessage(new Highlights()));
        }
    }

    /**
     * Handles the 'path calculation' event received from the client.
     */
    private final class SetPathHandler extends RequestHandler {

        public SetPathHandler() {
            super(PCEWEB_SET_PATH);
        }

        @Override
        public void process(long sid, ObjectNode payload) {
            String srcId = string(payload, SRCID);
            src = elementId(srcId);
            String dstId = string(payload, DSTID);
            dst = elementId(dstId);
            if (src.equals(dst)) {
                src = null;
            }

            String bandWidth = string(payload, BANDWIDTH);
            String bandWidthType = string(payload, BANDWIDTHTYPE);
            String costType = string(payload, COSTTYPE);
            String lspType = string(payload, LSPTYPE);
            String tunnelName = string(payload, TUNNEL_NAME);

            if (tunnelName.equals(STRING_NULL)) {
                log.error("tunnel name should not be empty");
                return;
            }

            if (pceService == null) {
                log.error("PCE service is not active");
                return;
            }

            if (lspType == null) {
                log.error("PCE setup path is failed.");
            }

            if ((src != null) && (dst != null)) {
                findAndSendPaths(src, dst, bandWidth, bandWidthType, costType, lspType, tunnelName);
            }
        }
    }

    /**
     * Handles the 'update path query' event received from the client.
     */
    private final class UpdatePathQueryHandler extends RequestHandler {

        public UpdatePathQueryHandler() {
            super(PCEWEB_UPDATE_PATH_QUERY);
        }

        @Override
        public void process(long sid, ObjectNode payload) {
            String srcId = string(payload, SRCID);
            ElementId src = elementId(srcId);
            String dstId = string(payload, DSTID);
            ElementId dst = elementId(dstId);
            Device srcDevice = deviceService.getDevice((DeviceId) src);
            Device dstDevice = deviceService.getDevice((DeviceId) dst);

            TunnelEndPoint tunSrc = IpTunnelEndPoint.ipTunnelPoint(IpAddress
                    .valueOf(srcDevice.annotations().value("lsrId")));
            TunnelEndPoint tunDst = IpTunnelEndPoint.ipTunnelPoint(IpAddress
                    .valueOf(dstDevice.annotations().value("lsrId")));

            Collection<Tunnel> tunnelSet = tunnelService.queryTunnel(tunSrc, tunDst);
            ObjectNode result = objectNode();
            ArrayNode arrayNode = arrayNode();
            for (Tunnel tunnel : tunnelSet) {
                if (tunnel.type() == MPLS) {
                    arrayNode.add(tunnel.tunnelId().toString());
                }
            }

            result.putArray(BUFFER_ARRAY).addAll(arrayNode);
            sendMessage(PCEWEB_SHOW_TUNNEL, sid, result);
        }
    }

    /**
     * Handles the 'update path' event received from the client.
     */
    private final class UpdatePathHandler extends RequestHandler {

        public UpdatePathHandler() {
            super(PCEWEB_UPDATE_PATH);
        }

        @Override
        public void process(long sid, ObjectNode payload) {
            String bandWidth = string(payload, BANDWIDTH);
            String bandWidthType = string(payload, BANDWIDTHTYPE);
            String costType = string(payload, COSTTYPE);
            String tunnelId = string(payload, TUNNEL_ID);

            if (tunnelId == null) {
                log.error("PCE update path is failed.");
            }

            findAndSendPathsUpdate(bandWidth, bandWidthType, costType, tunnelId);
        }
    }

    /**
     * Handles the 'remove path query' event received from the client.
     */
    private final class RemovePathQueryHandler extends RequestHandler {

        public RemovePathQueryHandler() {
            super(PCEWEB_REMOVE_PATH_QUERY);
        }

        @Override
        public void process(long sid, ObjectNode payload) {
            String srcId = string(payload, SRCID);
            ElementId src = elementId(srcId);
            String dstId = string(payload, DSTID);
            ElementId dst = elementId(dstId);

            Device srcDevice = deviceService.getDevice((DeviceId) src);
            Device dstDevice = deviceService.getDevice((DeviceId) dst);

            TunnelEndPoint tunSrc = IpTunnelEndPoint.ipTunnelPoint(IpAddress
                    .valueOf(srcDevice.annotations().value("lsrId")));
            TunnelEndPoint tunDst = IpTunnelEndPoint.ipTunnelPoint(IpAddress
                    .valueOf(dstDevice.annotations().value("lsrId")));

            Collection<Tunnel> tunnelSet = tunnelService.queryTunnel(tunSrc, tunDst);
            ObjectNode result = objectNode();
            ArrayNode arrayNode = arrayNode();

            for (Tunnel tunnel : tunnelSet) {
                if (tunnel.type() == MPLS) {
                    arrayNode.add(tunnel.tunnelId().toString());
                }
            }

            result.putArray(BUFFER_ARRAY).addAll(arrayNode);
            sendMessage(PCEWEB_SHOW_TUNNEL_REMOVE, sid, result);
        }
    }

    /**
     * Handles the 'remove path' event received from the client.
     */
    private final class RemovePathHandler extends RequestHandler {

        public RemovePathHandler() {
            super(PCEWEB_REMOVE_PATH);
        }

        @Override
        public void process(long sid, ObjectNode payload) {
            String tunnelId = string(payload, TUNNEL_ID);

            if (tunnelId == null) {
                log.error("PCE update path is failed.");
            }

            findAndSendPathsRemove(tunnelId);
        }
    }

    /**
     * Handles the 'show the existed tunnels' event received from the client.
     */
    private final class ShowTunnelHandler extends RequestHandler {

        public ShowTunnelHandler() {
            super(PCEWEB_QUERY_TUNNELS);
        }

        @Override
        public void process(long sid, ObjectNode payload) {
            findTunnelAndHighlights();
        }
    }

    /**
     * provides the element id.
     */
    private ElementId elementId(String id) {
        try {
            return DeviceId.deviceId(id);
        } catch (IllegalArgumentException e) {
            return HostId.hostId(id);
        }
    }

    /**
     * Handles the setup path and highlights the path.
     *
     * @param bandWidth
     * @param bandWidthType is the kbps or mbps
     * @param costType is igp or te
     * @param lspType is WITH_SIGNALLING,WITHOUT_SIGNALLING_AND_WITHOUT_SR or SR_WITHOUT_SIGNALLING
     * @param tunnelName tunnel id
     */
    private void findAndSendPaths(ElementId src, ElementId dst, String bandWidth, String bandWidthType,
                                    String costType, String lspType, String tunnelName) {
        log.debug("src={}; dst={};", src, dst);
        boolean path;
        List<Constraint> listConstrnt;

        listConstrnt = addBandwidthCostTypeConstraints(bandWidth, bandWidthType, costType);

        //LSP type
        LspType lspTypeVal = null;
        switch (lspType) {
            case LSP_TYPE_CR:
                lspTypeVal = LspType.WITH_SIGNALLING;
                break;
            case LSP_TYPE_SRBE:
                lspTypeVal = LspType.WITHOUT_SIGNALLING_AND_WITHOUT_SR;
                break;
            case LSP_TYPE_SRTE:
                lspTypeVal = LspType.SR_WITHOUT_SIGNALLING;
                break;
            default:
                break;
        }

        path = pceService.setupPath((DeviceId) src, (DeviceId) dst, tunnelName, listConstrnt, lspTypeVal, null);
        if (!path) {
             log.error("setup path is failed");
             return;
        }

        return;
    }

    /**
     * Handles the update path and highlights the path.
     *
     * @param bandWidth bandWidth
     * @param bandWidthType is the kbps or mbps
     * @param costType is igp or te
     * @param tunnelName tunnel id
     */
    private void findAndSendPathsUpdate(String bandWidth, String bandWidthType, String costType, String tunnelIdStr) {
        if (tunnelIdStr != null) {
            List<Constraint> listConstrnt;

            if (tunnelIdStr.equals(STRING_NULL)) {
                log.error("update path is failed");
                return;
            }

            if (pceService == null) {
                log.error("PCE service is not active");
                return;
            }

            listConstrnt = addBandwidthCostTypeConstraints(bandWidth, bandWidthType, costType);
            TunnelId tunnelId = TunnelId.valueOf(tunnelIdStr);
            boolean path = pceService.updatePath(tunnelId, listConstrnt);

            if (!path) {
                log.error("update path is failed");
                return;
            }
        }
        return;
    }

    /**
     * Handles the remove path and highlights the paths if existed.
     *
     * @param tunnelIdName tunnelId
     */
    private void findAndSendPathsRemove(String tunnelIdStr) {
        if (tunnelIdStr != null) {
            if (pceService == null) {
                log.error("PCE service is not active");
                return;
            }

            TunnelId tunnelId = TunnelId.valueOf(tunnelIdStr);
            boolean path = pceService.releasePath(tunnelId);
            if (!path) {
                log.error("remove path is failed");
                return;
            }
        }
        return;
    }

    private ImmutableSet.Builder<Link> buildPaths(ImmutableSet.Builder<Link> pathBuilder) {
        paths.forEach(path -> path.links().forEach(pathBuilder::add));
        return pathBuilder;
    }

    /**
     * Handles the preparation of constraints list with given bandwidth and cost-type.
     *
     * @param bandWidth bandWidth
     * @param bandWidthType is the kbps or mbps
     * @param costType is igp or te
     * @param listConstrnt list of constraints
     * @return
     */
    private List<Constraint> addBandwidthCostTypeConstraints(String bandWidth,
                                                             String bandWidthType,
                                                             String costType) {
        List<Constraint> listConstrnt = new LinkedList<>();
        //bandwidth
        double bwValue = 0.0;
        if (!bandWidth.equals(STRING_NULL)) {
            bwValue = Double.parseDouble(bandWidth);
        }
        if (bandWidthType.equals(BANDWIDTH_TYPE_KBPS)) {
            bwValue = bwValue * BANDWIDTH_KBPS;
        } else {
            bwValue = bwValue * BANDWIDTH_MBPS;
        }

        //Cost type
        CostConstraint.Type costTypeVal = null;
        switch (costType) {
        case COST_TYPE_IGP:
            costTypeVal = CostConstraint.Type.COST;
            break;
        case COST_TYPE_TE:
            costTypeVal = CostConstraint.Type.TE_COST;
            break;
        default:
            break;
        }

        if (bwValue != 0.0) {
            listConstrnt.add(BandwidthConstraint.of(bwValue, DataRateUnit.valueOf(BANDWIDTH_BPS)));
        }

        if (costTypeVal != null) {
            listConstrnt.add(CostConstraint.of(costTypeVal));
        }

        return listConstrnt;
    }

    /**
     * Handles the highlights of selected path.
     */
    private void hilightAndSendPaths(Highlights highlights) {
        PceWebLinkMap linkMap = new PceWebLinkMap();
        allPathLinks.forEach(linkMap::add);
        Set<Link> selectedPathLinks;

        selectedPathLinks = paths.isEmpty() ?
                ImmutableSet.of() : ImmutableSet.copyOf(paths.get(pathIndex).links());

        if (highlightDelay > 0) {
            highlights.delay(highlightDelay);
        }
        for (PceWebLink plink : linkMap.biLinks()) {
            plink.computeHilight(selectedPathLinks, allPathLinks);
            highlights.add(plink.highlight(null));
        }
        sendMessage(TopoJson.highlightsMessage(highlights));
    }

    /**
     *  Handles the addition of badge and highlights.
     *
     * @param highlights highlights
     * @param elemId device to be add badge
     * @param src device to be add badge
     * @return
     */
    private Highlights addBadge(Highlights highlights,
            String elemId, String src) {
        highlights = addDeviceBadge(highlights, elemId, src);
        return highlights;
    }

    /**
     * Handles the badge add and highlights.
     *
     * @param h highlights
     * @param elemId device to be add badge
     * @param type device badge value
     * @return highlights
     */
    private Highlights addDeviceBadge(Highlights h, String elemId, String type) {
        DeviceHighlight dh = new DeviceHighlight(elemId);
        dh.setBadge(createBadge(type));
        h.add(dh);
        return h;
    }

    /**
     * Handles the node badge add and highlights.
     *
     * @param type device badge value
     * @return badge of given node
     */
    private NodeBadge createBadge(String type) {
        return NodeBadge.text(type);
    }

    /**
     * Handles the event of topology listeners.
     */
    private class InternalTopologyListener implements TopologyListener {
        @Override
        public void event(TopologyEvent event) {
            highlightDelay = DELAY_MS;
            findTunnelAndHighlights();
            highlightDelay = 0;
        }
    }

    /**
     * Handles the event of tunnel listeners.
     */
    private class InnerPceWebTunnelListener implements TunnelListener {
        @Override
        public void event(TunnelEvent event) {
            Tunnel tunnel = event.subject();
            if (tunnel.type() == MPLS) {
                highlightDelay = DELAY_MS;
                findTunnelAndHighlights();
                highlightDelay = 0;
            }
        }
    }

   /**
     * Handles the event of topology listeners.
    */
    private void findTunnelAndHighlights() {
        Collection<Tunnel> tunnelSet = null;
        tunnelSet = tunnelService.queryTunnel(MPLS);
        if (tunnelSet.size() == 0) {
            log.warn("Tunnel does not exist");
            return;
        }

        paths.removeAll(paths);
        Highlights highlights = new Highlights();
        for (Tunnel tunnel : tunnelSet) {
            if (tunnel.path() == null) {
                log.error("path does not exist");
                return;
            }
            Link firstLink = tunnel.path().links().get(0);
            if (firstLink != null) {
                if (firstLink.src() != null) {
                        highlights = addBadge(highlights, firstLink.src().deviceId().toString(), SRC);
                }
            }
            Link lastLink = tunnel.path().links().get(tunnel.path().links().size() - 1);
            if (lastLink != null) {
                if (lastLink.dst() != null) {
                        highlights = addBadge(highlights, lastLink.dst().deviceId().toString(), DST);
                }
            }
            paths.add(tunnel.path());
        }

        ImmutableSet.Builder<Link> builder = ImmutableSet.builder();
        allPathLinks = buildPaths(builder).build();
        hilightAndSendPaths(highlights);
    }
}
