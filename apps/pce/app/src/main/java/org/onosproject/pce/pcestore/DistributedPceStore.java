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
package org.onosproject.pce.pcestore;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.ImmutableSet;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.HashSet;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.apache.felix.scr.annotations.Service;

import org.onlab.util.KryoNamespace;
import org.onosproject.incubator.net.tunnel.Tunnel;
import org.onosproject.incubator.net.tunnel.Tunnel.State;
import org.onosproject.incubator.net.tunnel.TunnelId;
import org.onosproject.incubator.net.resource.label.LabelResource;
import org.onosproject.incubator.net.resource.label.LabelResourceId;
import org.onosproject.net.LinkKey;
import org.onosproject.net.DeviceId;
import org.onosproject.net.Link;
import org.onosproject.pce.pceservice.constraint.CapabilityConstraint;
import org.onosproject.pce.pceservice.constraint.CostConstraint;
import org.onosproject.pce.pceservice.LspType;
import org.onosproject.pce.pceservice.constraint.PceBandwidthConstraint;
import org.onosproject.pce.pcestore.api.LspLocalLabelInfo;
import org.onosproject.pce.pcestore.api.PceStore;
import org.onosproject.store.serializers.KryoNamespaces;
import org.onosproject.pce.pceservice.constraint.SharedBandwidthConstraint;
import org.onosproject.store.service.ConsistentMap;
import org.onosproject.store.service.DistributedSet;
import org.onosproject.store.service.Serializer;
import org.onosproject.store.service.StorageService;
import org.onosproject.store.service.Versioned;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages the pool of available labels to devices, links and tunnels.
 */
@Component(immediate = true)
@Service
public class DistributedPceStore implements PceStore {

    private static final String DEVICE_ID_NULL = "Device ID cannot be null";
    private static final String DEVICE_LABEL_STORE_INFO_NULL = "Device Label Store cannot be null";
    private static final String LABEL_RESOURCE_ID_NULL = "Label Resource Id cannot be null";
    private static final String LABEL_RESOURCE_LIST_NULL = "Label Resource List cannot be null";
    private static final String LABEL_RESOURCE_NULL = "Label Resource cannot be null";
    private static final String LINK_NULL = "LINK cannot be null";
    private static final String LSP_LOCAL_LABEL_INFO_NULL = "LSP Local Label Info cannot be null";
    private static final String PATH_INFO_NULL = "Path Info cannot be null";
    private static final String PCECC_TUNNEL_INFO_NULL = "PCECC Tunnel Info cannot be null";
    private static final String TUNNEL_ID_NULL = "Tunnel Id cannot be null";
    private static final String TUNNEL_CONSUMER_ID_NULL = "Tunnel consumer Id cannot be null";

    private final Logger log = LoggerFactory.getLogger(getClass());

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected StorageService storageService;

    // Mapping device with global node label
    private ConsistentMap<DeviceId, LabelResourceId> globalNodeLabelMap;

    // Mapping link with adjacency label
    private ConsistentMap<Link, LabelResourceId> adjLabelMap;

    // Mapping tunnel with device local info with tunnel consumer id
    private ConsistentMap<TunnelId, PceccTunnelInfo> tunnelInfoMap;

    // Map to store Parent tunnel status and child tunnel status, parent tunnel status should be down till all
    // child tunnel status is up, if any child tunnel status goes down, parent tunnel status should be down.
    private ConsistentMap<TunnelId, Map<TunnelId, State>> parentChildTunnelStatusMap;

    // List of Failed path info
    private DistributedSet<PcePathInfo> failedPathSet;

    // Mapping tunnel with link key with local reserved bandwidth
    private ConsistentMap<LinkKey, Double> localReservedBw;

    // Locally maintain LSRID to device id mapping for better performance.
    private Map<String, DeviceId> lsrIdDeviceIdMap = new HashMap<>();

    // List of PCC LSR ids whose BGP device information was not available to perform
    // label db sync.
    private HashSet<DeviceId> pendinglabelDbSyncPccMap = new HashSet();
    // Locally maintain unreserved bandwidth of each link.
    private Map<LinkKey, Set<Double>> unResvBw = new HashMap<>();
    private static final Serializer SERIALIZER = Serializer
            .using(new KryoNamespace.Builder().register(KryoNamespaces.API)
                    .register(PcePathInfo.class)
                    .register(CostConstraint.class)
                    .register(CostConstraint.Type.class)
                    .register(PceBandwidthConstraint.class)
                    .register(SharedBandwidthConstraint.class)
                    .register(CapabilityConstraint.class)
                    .register(CapabilityConstraint.CapabilityType.class)
                    .register(LspType.class)
                    .build());
    @Activate
    protected void activate() {
        globalNodeLabelMap = storageService.<DeviceId, LabelResourceId>consistentMapBuilder()
                .withName("onos-pce-globalnodelabelmap")
                .withSerializer(Serializer.using(
                        new KryoNamespace.Builder()
                                .register(KryoNamespaces.API)
                                .register(LabelResourceId.class)
                                .build()))
                .build();

        adjLabelMap = storageService.<Link, LabelResourceId>consistentMapBuilder()
                .withName("onos-pce-adjlabelmap")
                .withSerializer(Serializer.using(
                        new KryoNamespace.Builder()
                                .register(KryoNamespaces.API)
                                .register(Link.class,
                                          LabelResource.class,
                                          LabelResourceId.class)
                                .build()))
                .build();

        tunnelInfoMap = storageService.<TunnelId, PceccTunnelInfo>consistentMapBuilder()
                .withName("onos-pce-tunnelinfomap")
                .withSerializer(Serializer.using(
                        new KryoNamespace.Builder()
                                .register(KryoNamespaces.API)
                                .register(TunnelId.class,
                                        PceccTunnelInfo.class,
                                        DefaultLspLocalLabelInfo.class,
                                        LabelResourceId.class)
                                .build()))
                .build();

        failedPathSet = storageService.<PcePathInfo>setBuilder()
                .withName("failed-path-info")
                .withSerializer(SERIALIZER)
                .build()
                .asDistributedSet();

        localReservedBw = storageService.<LinkKey, Double>consistentMapBuilder()
                .withName("onos-pce-localResrvBw")
                .withSerializer(Serializer.using(
                        new KryoNamespace.Builder()
                                .register(KryoNamespaces.API)
                                .register(LinkKey.class)
                                .build()))
                .build();

        parentChildTunnelStatusMap = storageService.<TunnelId, Map<TunnelId, State>>consistentMapBuilder()
                .withName("onos-pce-parentChild")
                .withSerializer(Serializer.using(
                        new KryoNamespace.Builder()
                                .register(KryoNamespaces.API)
                                .register(TunnelId.class,
                                        Tunnel.State.class)
                                .build()))
                .build();

        log.info("Started");
    }

    @Deactivate
    protected void deactivate() {
        log.info("Stopped");
    }

    @Override
    public boolean existsGlobalNodeLabel(DeviceId id) {
        checkNotNull(id, DEVICE_ID_NULL);
        return globalNodeLabelMap.containsKey(id);
    }

    @Override
    public boolean existsAdjLabel(Link link) {
        checkNotNull(link, LINK_NULL);
        return adjLabelMap.containsKey(link);
    }

    @Override
    public boolean existsTunnelInfo(TunnelId tunnelId) {
        checkNotNull(tunnelId, TUNNEL_ID_NULL);
        return tunnelInfoMap.containsKey(tunnelId);
    }

    @Override
    public boolean existsFailedPathInfo(PcePathInfo failedPathInfo) {
        checkNotNull(failedPathInfo, PATH_INFO_NULL);
        return failedPathSet.contains(failedPathInfo);
    }

    @Override
    public int getGlobalNodeLabelCount() {
        return globalNodeLabelMap.size();
    }

    @Override
    public int getAdjLabelCount() {
        return adjLabelMap.size();
    }

    @Override
    public int getTunnelInfoCount() {
        return tunnelInfoMap.size();
    }

    @Override
    public int getFailedPathInfoCount() {
        return failedPathSet.size();
    }

    @Override
    public Map<DeviceId, LabelResourceId> getGlobalNodeLabels() {
       return globalNodeLabelMap.entrySet().stream()
                 .collect(Collectors.toMap(Map.Entry::getKey, e -> (LabelResourceId) e.getValue().value()));
    }

    @Override
    public Map<Link, LabelResourceId> getAdjLabels() {
       return adjLabelMap.entrySet().stream()
                 .collect(Collectors.toMap(Map.Entry::getKey, e -> (LabelResourceId) e.getValue().value()));
    }

    @Override
    public Map<TunnelId, PceccTunnelInfo> getTunnelInfos() {
       return tunnelInfoMap.entrySet().stream()
               .collect(Collectors.toMap(Map.Entry::getKey, e -> (PceccTunnelInfo) e.getValue().value()));
    }

    @Override
    public Iterable<PcePathInfo> getFailedPathInfos() {
       return ImmutableSet.copyOf(failedPathSet);
    }

    @Override
    public LabelResourceId getGlobalNodeLabel(DeviceId id) {
        checkNotNull(id, DEVICE_ID_NULL);
        return globalNodeLabelMap.get(id) == null ? null : globalNodeLabelMap.get(id).value();
    }

    @Override
    public LabelResourceId getAdjLabel(Link link) {
        checkNotNull(link, LINK_NULL);
        return adjLabelMap.get(link) == null ? null : adjLabelMap.get(link).value();
    }

    @Override
    public PceccTunnelInfo getTunnelInfo(TunnelId tunnelId) {
        checkNotNull(tunnelId, TUNNEL_ID_NULL);
        return tunnelInfoMap.get(tunnelId) == null ? null : tunnelInfoMap.get(tunnelId).value();
    }

    @Override
    public void addGlobalNodeLabel(DeviceId deviceId, LabelResourceId labelId) {
        checkNotNull(deviceId, DEVICE_ID_NULL);
        checkNotNull(labelId, LABEL_RESOURCE_ID_NULL);

        globalNodeLabelMap.put(deviceId, labelId);
    }

    @Override
    public void addAdjLabel(Link link, LabelResourceId labelId) {
        checkNotNull(link, LINK_NULL);
        checkNotNull(labelId, LABEL_RESOURCE_ID_NULL);

        adjLabelMap.put(link, labelId);
    }

    @Override
    public void addTunnelInfo(TunnelId tunnelId, PceccTunnelInfo pceccTunnelInfo) {
        checkNotNull(tunnelId, TUNNEL_ID_NULL);
        checkNotNull(pceccTunnelInfo, PCECC_TUNNEL_INFO_NULL);

        tunnelInfoMap.put(tunnelId, pceccTunnelInfo);
    }

    @Override
    public void addFailedPathInfo(PcePathInfo failedPathInfo) {
        checkNotNull(failedPathInfo, PATH_INFO_NULL);
        failedPathSet.add(failedPathInfo);
    }

    @Override
    public boolean updateTunnelInfo(TunnelId tunnelId, List<LspLocalLabelInfo> lspLocalLabelInfoList) {
        checkNotNull(tunnelId, TUNNEL_ID_NULL);
        checkNotNull(lspLocalLabelInfoList, LSP_LOCAL_LABEL_INFO_NULL);

        if (!tunnelInfoMap.containsKey((tunnelId))) {
            log.debug("Tunnel info does not exist whose tunnel id is {}.", tunnelId.toString());
            return false;
        }

        PceccTunnelInfo tunnelInfo = tunnelInfoMap.get(tunnelId).value();
        tunnelInfo.lspLocalLabelInfoList(lspLocalLabelInfoList);
        tunnelInfoMap.put(tunnelId, tunnelInfo);

        return true;
    }

    @Override
    public boolean removeGlobalNodeLabel(DeviceId id) {
        checkNotNull(id, DEVICE_ID_NULL);

        if (globalNodeLabelMap.remove(id) == null) {
            log.error("SR-TE node label deletion for device {} has failed.", id.toString());
            return false;
        }
        return true;
    }

    @Override
    public boolean removeAdjLabel(Link link) {
        checkNotNull(link, LINK_NULL);

        if (adjLabelMap.remove(link) == null) {
            log.error("Adjacency label deletion for link {} hash failed.", link.toString());
            return false;
        }
        return true;
    }

    @Override
    public boolean removeTunnelInfo(TunnelId tunnelId) {
        checkNotNull(tunnelId, TUNNEL_ID_NULL);

        if (tunnelInfoMap.remove(tunnelId) == null) {
            log.error("Tunnel info deletion for tunnel id {} has failed.", tunnelId.toString());
            return false;
        }
        return true;
    }

    @Override
    public boolean removeFailedPathInfo(PcePathInfo failedPathInfo) {
        checkNotNull(failedPathInfo, PATH_INFO_NULL);

        if (!failedPathSet.remove(failedPathInfo)) {
            log.error("Failed path info {} deletion has failed.", failedPathInfo.toString());
            return false;
        }
        return true;
    }

    @Override
    public boolean addLsrIdDevice(String lsrId, DeviceId deviceId) {
        checkNotNull(lsrId);
        checkNotNull(deviceId);

        lsrIdDeviceIdMap.put(lsrId, deviceId);
        return true;
    }

    @Override
    public boolean removeLsrIdDevice(String lsrId) {
        checkNotNull(lsrId);

        lsrIdDeviceIdMap.remove(lsrId);
        return true;
    }

    @Override
    public DeviceId getLsrIdDevice(String lsrId) {
        checkNotNull(lsrId);

        return lsrIdDeviceIdMap.get(lsrId);

    }

    @Override
    public boolean addUnreservedBw(LinkKey linkkey, Set<Double> bandwidth) {
        checkNotNull(linkkey);
        checkNotNull(bandwidth);
        unResvBw.put(linkkey, bandwidth);
        return true;
    }

    @Override
    public boolean removeUnreservedBw(LinkKey linkkey) {
        checkNotNull(linkkey);
        unResvBw.remove(linkkey);
        return true;
    }

    @Override
    public Set<Double> getUnreservedBw(LinkKey linkkey) {
        checkNotNull(linkkey);
        return unResvBw.get(linkkey);
    }

    @Override
    public boolean allocLocalReservedBw(LinkKey linkkey, Double bandwidth) {
        checkNotNull(linkkey);
        checkNotNull(bandwidth);

        Versioned<Double> allocatedBw = localReservedBw.get(linkkey);
        if (allocatedBw != null) {
            localReservedBw.put(linkkey, (allocatedBw.value() + bandwidth));
        } else {
            localReservedBw.put(linkkey, bandwidth);
        }

        return true;
    }

    @Override
    public boolean releaseLocalReservedBw(LinkKey linkkey, Double bandwidth) {
        checkNotNull(linkkey);
        checkNotNull(bandwidth);
        Versioned<Double> allocatedBw = localReservedBw.get(linkkey);
        if (allocatedBw == null || allocatedBw.value() < bandwidth) {
            return false;
        }

        Double releasedBw = allocatedBw.value() - bandwidth;
        if (releasedBw == 0.0) {
            localReservedBw.remove(linkkey);
        } else {
            localReservedBw.put(linkkey, releasedBw);
        }
        return true;
    }

    @Override
    public Versioned<Double> getAllocatedLocalReservedBw(LinkKey linkkey) {
        checkNotNull(linkkey);
        Versioned<Double> bw = localReservedBw.get(linkkey);
        return localReservedBw.get(linkkey);
    }

    @Override
    public boolean addParentTunnel(TunnelId tunnelId, State status) {
        checkNotNull(tunnelId);
        checkNotNull(status);

        if (parentChildTunnelStatusMap.get(tunnelId) == null) {
            Map<TunnelId, State> tunnelStatus = new HashMap<>();
            tunnelStatus.put(tunnelId, status);
            parentChildTunnelStatusMap.put(tunnelId, tunnelStatus);
            return true;
        }
        return false;
    }

    @Override
    public TunnelId parentTunnel(TunnelId tunnelId) {
        checkNotNull(tunnelId);

        if (parentChildTunnelStatusMap.get(tunnelId) != null) {
            return tunnelId;
        }


        //first get corresponding parent and update child
        Iterator parentTunnelIds = parentChildTunnelStatusMap.keySet().iterator();
        while (parentTunnelIds.hasNext()) {
            TunnelId id = (TunnelId) parentTunnelIds.next();
            if (parentChildTunnelStatusMap.get(id).value().keySet().contains(tunnelId)) {
                return id;
            }
        }

        return null;
    }

    @Override
    public Map<TunnelId, State> childTunnel(TunnelId tunnelId) {
        checkNotNull(tunnelId);

        if (parentChildTunnelStatusMap.get(tunnelId) == null) {
            return null;
        }

        return parentChildTunnelStatusMap.get(tunnelId).value();

    }

    @Override
    public boolean removeParentTunnel(TunnelId tunnelId) {
        checkNotNull(tunnelId);
        if (parentChildTunnelStatusMap.get(tunnelId) != null) {
            Map<TunnelId, State> childTunnels = parentChildTunnelStatusMap.get(tunnelId).value();
            if (!childTunnels.isEmpty()) {
                //childTunnels.keySet().forEach(childTunnels::remove);
                childTunnels.clear();
            }
            parentChildTunnelStatusMap.remove(tunnelId);
        }
        return true;
    }

    @Override
    public boolean updateTunnelStatus(TunnelId tunnelId, State status) {
        checkNotNull(tunnelId);
        checkNotNull(status);

        if (parentChildTunnelStatusMap.get(tunnelId) != null) {
            Map<TunnelId, State> childTunnels = parentChildTunnelStatusMap.get(tunnelId).value();
            childTunnels.replace(tunnelId, status);
            parentChildTunnelStatusMap.replace(tunnelId, childTunnels);
            return true;
        }

        //first get corresponding parent and update child
        Iterator parentTunnelIds = parentChildTunnelStatusMap.keySet().iterator();
        while (parentTunnelIds.hasNext()) {
            TunnelId id = (TunnelId) parentTunnelIds.next();
            if (parentChildTunnelStatusMap.get(id).value().keySet().contains(tunnelId)) {

                Map<TunnelId, State> childTunnels = parentChildTunnelStatusMap.get(id).value();
                childTunnels.replace(tunnelId, status);
                //parentChildTunnelStatusMap.get(id).value().replace(tunnelId, status);
                parentChildTunnelStatusMap.replace(id, childTunnels);
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean addChildTunnel(TunnelId parentId, TunnelId childId, State status) {
        if (parentChildTunnelStatusMap.get(parentId) != null) {
            Map<TunnelId, State> childTunnels = parentChildTunnelStatusMap.get(parentId).value();
            if (childTunnels.get(childId) == null) {
                childTunnels.put(childId, status);
            } else {
                childTunnels.replace(childId, status);
            }
            parentChildTunnelStatusMap.replace(parentId, childTunnels);
            return true;
        }
        return false;
    }

    @Override
    public boolean removeChildTunnel(TunnelId parentId, TunnelId childId) {
        if (parentChildTunnelStatusMap.get(parentId) != null) {
            Map<TunnelId, State> childTunnels = parentChildTunnelStatusMap.get(parentId).value();
            if (childTunnels.get(childId) != null) {
                childTunnels.remove(childId);
                parentChildTunnelStatusMap.replace(parentId, childTunnels);
                return true;
            }
        }
        return false;
    }

    @Override
    public State tunnelStatus(TunnelId tunnelId) {
        if (parentChildTunnelStatusMap.get(tunnelId) != null) {
            Map<TunnelId, State> childTunnels = parentChildTunnelStatusMap.get(tunnelId).value();
            if (childTunnels.isEmpty()) {
                return State.INIT;
            }
            return childTunnels.get(tunnelId);
        }
        // first get corresponding parent and update child
        Iterator parentTunnels = parentChildTunnelStatusMap.entrySet().iterator();
        while (parentTunnels.hasNext()) {
            TunnelId id = (TunnelId) parentTunnels.next();
            Map<TunnelId, State> childTunnel = parentChildTunnelStatusMap.get(id).value();
            for (Map.Entry<TunnelId, State> tunnel : childTunnel.entrySet()) {
                if (tunnelId.equals(tunnel.getKey())) {
                    return childTunnel.get(tunnelId);
                }
            }
        }
        return State.INIT;
    }

    @Override
    public boolean isAllChildUp(TunnelId parentId) {
        if (parentChildTunnelStatusMap.get(parentId) != null) {
            Map<TunnelId, State> childTunnels = parentChildTunnelStatusMap.get(parentId).value();
            for (Map.Entry<TunnelId, State> childTunnel : childTunnels.entrySet()) {
                if (parentId.equals(childTunnel.getKey())) {
                    continue;
                }
                if (!childTunnel.getValue().equals(State.ACTIVE)) {
                    return false;
                }
            }
            if (childTunnels.size() > 0) {
                return true;
            }
        }
        return false;
    }
    @Override
    public boolean addPccLsr(DeviceId lsrId) {
        checkNotNull(lsrId);
        pendinglabelDbSyncPccMap.add(lsrId);
        return true;
    }
    @Override
    public boolean removePccLsr(DeviceId lsrId) {
        checkNotNull(lsrId);
        pendinglabelDbSyncPccMap.remove(lsrId);
        return true;
    }
    @Override
    public boolean hasPccLsr(DeviceId lsrId) {
        checkNotNull(lsrId);
        return pendinglabelDbSyncPccMap.contains(lsrId);

    }

}
