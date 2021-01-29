/*
 SPDX-License-Identifier: LicenseRef-ONF-Member-1.0
 SPDX-FileCopyrightText: 2020-present Open Networking Foundation <info@opennetworking.org>
 */
package org.omecproject.up4.behavior;

import com.google.common.collect.Maps;
import org.apache.commons.lang3.tuple.Pair;
import org.omecproject.up4.ForwardingActionRule;
import org.omecproject.up4.GtpTunnel;
import org.omecproject.up4.PacketDetectionRule;
import org.omecproject.up4.Up4Translator;
import org.omecproject.up4.UpfInterface;
import org.omecproject.up4.UpfRuleIdentifier;
import org.omecproject.up4.impl.NorthConstants;
import org.omecproject.up4.impl.SouthConstants;
import org.onlab.packet.Ip4Address;
import org.onlab.packet.Ip4Prefix;
import org.onlab.util.ImmutableByteSequence;
import org.onlab.util.KryoNamespace;
import org.onosproject.core.ApplicationId;
import org.onosproject.net.DeviceId;
import org.onosproject.net.flow.DefaultFlowRule;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.FlowRule;
import org.onosproject.net.flow.criteria.PiCriterion;
import org.onosproject.net.pi.model.PiActionId;
import org.onosproject.net.pi.model.PiTableId;
import org.onosproject.net.pi.runtime.PiAction;
import org.onosproject.net.pi.runtime.PiActionParam;
import org.onosproject.net.pi.runtime.PiExactFieldMatch;
import org.onosproject.net.pi.runtime.PiLpmFieldMatch;
import org.onosproject.net.pi.runtime.PiMatchKey;
import org.onosproject.net.pi.runtime.PiTableAction;
import org.onosproject.net.pi.runtime.PiTableEntry;
import org.onosproject.net.pi.runtime.PiTernaryFieldMatch;
import org.onosproject.store.serializers.KryoNamespaces;
import org.onosproject.store.service.ConsistentMap;
import org.onosproject.store.service.MapEvent;
import org.onosproject.store.service.MapEventListener;
import org.onosproject.store.service.Serializer;
import org.onosproject.store.service.StorageService;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

/**
 * Utility class for transforming PiTableEntries to classes more specific to the UPF pipelines,
 * like PacketDetectionRule and ForwardingActionRule.
 */
@Component(immediate = true,
        service = {Up4Translator.class})
public class Up4TranslatorImpl implements Up4Translator {
    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected StorageService storageService;

    private final Logger log = LoggerFactory.getLogger(getClass());
    protected static final String FAR_ID_MAP_NAME = "up4-far-id";
    protected static final KryoNamespace.Builder SERIALIZER = KryoNamespace.newBuilder()
            .register(KryoNamespaces.API)
            .register(UpfRuleIdentifier.class);

    // Distributed local FAR ID to global FAR ID mapping
    protected ConsistentMap<UpfRuleIdentifier, Integer> farIdMap;
    private MapEventListener<UpfRuleIdentifier, Integer> farIdMapListener;
    // Local, reversed copy of farIdMapper for better reverse lookup performance
    protected Map<Integer, UpfRuleIdentifier> reverseFarIdMap;

    private final ImmutableByteSequence allOnes32 = ImmutableByteSequence.ofOnes(4);
    private int nextGlobalFarId = 1;

    @Activate
    protected void activate() {
        // Allow unit test to inject farIdMap here.
        if (storageService != null) {
            farIdMap = storageService.<UpfRuleIdentifier, Integer>consistentMapBuilder()
                    .withName(FAR_ID_MAP_NAME)
                    .withRelaxedReadConsistency()
                    .withSerializer(Serializer.using(SERIALIZER.build()))
                    .build();
        }
        farIdMapListener = new FarIdMapListener();
        farIdMap.addListener(farIdMapListener);

        reverseFarIdMap = Maps.newHashMap();
        farIdMap.entrySet().forEach(entry -> reverseFarIdMap.put(entry.getValue().value(), entry.getKey()));

        log.info("Started");
    }

    @Deactivate
    protected void deactivate() {
        farIdMap.removeListener(farIdMapListener);
        farIdMap.destroy();
        reverseFarIdMap.clear();

        log.info("Stopped");
    }

    @Override
    public void reset() {
        farIdMap.clear();
        reverseFarIdMap.clear();
        nextGlobalFarId = 0;
    }

    @Override
    public Map<UpfRuleIdentifier, Integer> getFarIdMap() {
        return Map.copyOf(farIdMap.asJavaMap());
    }

    @Override
    public int globalFarIdOf(UpfRuleIdentifier farIdPair) {
        int globalFarId = farIdMap.compute(farIdPair,
                (k, existingId) -> {
                    return Objects.requireNonNullElseGet(existingId, () -> nextGlobalFarId++);
                }).value();
        log.info("{} translated to GlobalFarId={}", farIdPair, globalFarId);
        return globalFarId;
    }

    @Override
    public int globalFarIdOf(ImmutableByteSequence pfcpSessionId, int sessionLocalFarId) {
        UpfRuleIdentifier farId = new UpfRuleIdentifier(pfcpSessionId, sessionLocalFarId);
        return globalFarIdOf(farId);

    }

    /**
     * Get the corresponding PFCP session ID and session-local FAR ID from a globally unique FAR ID, or return
     * null if no such mapping is found.
     *
     * @param globalFarId globally unique FAR ID
     * @return the corresponding PFCP session ID and session-local FAR ID, as a RuleIdentifier
     */
    private UpfRuleIdentifier localFarIdOf(int globalFarId) {
        return reverseFarIdMap.get(globalFarId);
    }

    @Override
    public boolean isUp4Pdr(PiTableEntry entry) {
        return entry.table().equals(NorthConstants.PDR_TBL);
    }

    @Override
    public boolean isFabricPdr(FlowRule entry) {
        return entry.table().equals(SouthConstants.FABRIC_INGRESS_SPGW_UPLINK_PDRS)
                || entry.table().equals(SouthConstants.FABRIC_INGRESS_SPGW_DOWNLINK_PDRS);
    }

    @Override
    public boolean isUp4Far(PiTableEntry entry) {
        return entry.table().equals(NorthConstants.FAR_TBL);
    }

    @Override
    public boolean isFabricFar(FlowRule entry) {
        return entry.table().equals(SouthConstants.FABRIC_INGRESS_SPGW_FARS);
    }

    @Override
    public boolean isFabricInterface(FlowRule entry) {
        return entry.table().equals(SouthConstants.FABRIC_INGRESS_SPGW_INTERFACES);
    }

    @Override
    public boolean isUp4Interface(PiTableEntry entry) {
        return entry.table().equals(NorthConstants.IFACE_TBL);
    }

    @Override
    public PacketDetectionRule fabricEntryToPdr(FlowRule entry)
            throws Up4TranslationException {
        var pdrBuilder = PacketDetectionRule.builder();
        Pair<PiCriterion, PiTableAction> matchActionPair = TranslatorUtil.fabricEntryToPiPair(entry);
        PiCriterion match = matchActionPair.getLeft();
        PiAction action = (PiAction) matchActionPair.getRight();

        // Grab keys and parameters that are present for all PDRs
        int globalFarId = TranslatorUtil.getParamInt(action, SouthConstants.FAR_ID);
        UpfRuleIdentifier farId = localFarIdOf(globalFarId);
        pdrBuilder.withCounterId(TranslatorUtil.getParamInt(action, SouthConstants.CTR_ID))
                .withLocalFarId(farId.getSessionLocalId())
                .withSessionId(farId.getPfcpSessionId());

        if (TranslatorUtil.fieldIsPresent(match, SouthConstants.HDR_TEID)) {
            // F-TEID is only present for GTP-matching PDRs
            ImmutableByteSequence teid = TranslatorUtil.getFieldValue(match, SouthConstants.HDR_TEID);
            Ip4Address tunnelDst = TranslatorUtil.getFieldAddress(match, SouthConstants.HDR_TUNNEL_IPV4_DST);
            pdrBuilder.withTeid(teid)
                    .withTunnelDst(tunnelDst);
        } else if (TranslatorUtil.fieldIsPresent(match, SouthConstants.HDR_UE_ADDR)) {
            // And UE address is only present for non-GTP-matching PDRs
            pdrBuilder.withUeAddr(TranslatorUtil.getFieldAddress(match, SouthConstants.HDR_UE_ADDR));
        } else {
            throw new Up4TranslationException("Read malformed PDR from dataplane!:" + entry);
        }

        return pdrBuilder.build();
    }

    @Override
    public PacketDetectionRule up4EntryToPdr(PiTableEntry entry)
            throws Up4TranslationException {
        var pdrBuilder = PacketDetectionRule.builder();

        int srcInterface = TranslatorUtil.getFieldInt(entry, NorthConstants.SRC_IFACE_KEY);
        if (srcInterface == NorthConstants.IFACE_ACCESS) {
            // GTP-matching PDRs will match on the F-TEID (tunnel destination address + TEID)
            pdrBuilder.withTunnel(TranslatorUtil.getFieldValue(entry, NorthConstants.TEID_KEY),
                    TranslatorUtil.getFieldAddress(entry, NorthConstants.TUNNEL_DST_KEY));
        } else if (srcInterface == NorthConstants.IFACE_CORE) {
            // Non-GTP-matching PDRs will match on the UE address
            pdrBuilder.withUeAddr(TranslatorUtil.getFieldAddress(entry, NorthConstants.UE_ADDR_KEY));
        } else {
            throw new Up4TranslationException("Flexible PDRs not yet supported.");
        }

        // Now get the action parameters, if they are present (entries from delete writes don't have parameters)
        PiAction action = (PiAction) entry.action();
        PiActionId actionId = action.id();
        if (actionId.equals(NorthConstants.LOAD_PDR) && !action.parameters().isEmpty()) {
            ImmutableByteSequence sessionId = TranslatorUtil.getParamValue(entry, NorthConstants.SESSION_ID_PARAM);
            int localFarId = TranslatorUtil.getParamInt(entry, NorthConstants.FAR_ID_PARAM);
            pdrBuilder.withSessionId(sessionId)
                    .withCounterId(TranslatorUtil.getParamInt(entry, NorthConstants.CTR_ID))
                    .withLocalFarId(localFarId);
        }
        return pdrBuilder.build();
    }


    @Override
    public ForwardingActionRule fabricEntryToFar(FlowRule entry)
            throws Up4TranslationException {
        var farBuilder = ForwardingActionRule.builder();
        Pair<PiCriterion, PiTableAction> matchActionPair = TranslatorUtil.fabricEntryToPiPair(entry);
        PiCriterion match = matchActionPair.getLeft();
        PiAction action = (PiAction) matchActionPair.getRight();

        int globalFarId = TranslatorUtil.getFieldInt(match, SouthConstants.HDR_FAR_ID);
        UpfRuleIdentifier farId = localFarIdOf(globalFarId);

        boolean dropFlag = TranslatorUtil.getParamInt(action, SouthConstants.DROP) > 0;
        boolean notifyFlag = TranslatorUtil.getParamInt(action, SouthConstants.NOTIFY_CP) > 0;

        // Match keys
        farBuilder.withSessionId(farId.getPfcpSessionId())
                .setFarId(farId.getSessionLocalId());

        // Parameters common to all types of FARs
        farBuilder.setDropFlag(dropFlag)
                .setNotifyFlag(notifyFlag);

        PiActionId actionId = action.id();

        if (actionId.equals(SouthConstants.FABRIC_INGRESS_SPGW_LOAD_TUNNEL_FAR)
                || actionId.equals(SouthConstants.FABRIC_INGRESS_SPGW_LOAD_DBUF_FAR)) {
            // Grab parameters specific to encapsulating FARs if they're present
            Ip4Address tunnelSrc = TranslatorUtil.getParamAddress(action, SouthConstants.TUNNEL_SRC_ADDR);
            Ip4Address tunnelDst = TranslatorUtil.getParamAddress(action, SouthConstants.TUNNEL_DST_ADDR);
            ImmutableByteSequence teid = TranslatorUtil.getParamValue(action, SouthConstants.TEID);
            short tunnelSrcPort = (short) TranslatorUtil.getParamInt(action, SouthConstants.TUNNEL_SRC_PORT);

            farBuilder.setBufferFlag(actionId.equals(SouthConstants.FABRIC_INGRESS_SPGW_LOAD_DBUF_FAR));

            farBuilder.setTunnel(
                    GtpTunnel.builder()
                            .setSrc(tunnelSrc)
                            .setDst(tunnelDst)
                            .setTeid(teid)
                            .setSrcPort(tunnelSrcPort)
                            .build());
        }
        return farBuilder.build();
    }

    @Override
    public ForwardingActionRule up4EntryToFar(PiTableEntry entry)
            throws Up4TranslationException {
        // First get the match keys
        ImmutableByteSequence sessionId = TranslatorUtil.getFieldValue(entry, NorthConstants.SESSION_ID_KEY);
        int localFarId = TranslatorUtil.getFieldInt(entry, NorthConstants.FAR_ID_KEY);
        var farBuilder = ForwardingActionRule.builder()
                .setFarId(localFarId)
                .withSessionId(sessionId);

        // Now get the action parameters, if they are present (entries from delete writes don't have parameters)
        PiAction action = (PiAction) entry.action();
        PiActionId actionId = action.id();
        if (!action.parameters().isEmpty()) {
            // Parameters that all types of fars have
            farBuilder.setDropFlag(TranslatorUtil.getParamInt(entry, NorthConstants.DROP_FLAG) > 0)
                    .setNotifyFlag(TranslatorUtil.getParamInt(entry, NorthConstants.NOTIFY_FLAG) > 0);
            if (actionId.equals(NorthConstants.LOAD_FAR_TUNNEL)) {
                // Parameters exclusive to encapsulating FARs
                farBuilder.setTunnel(
                        TranslatorUtil.getParamAddress(entry, NorthConstants.TUNNEL_SRC_PARAM),
                        TranslatorUtil.getParamAddress(entry, NorthConstants.TUNNEL_DST_PARAM),
                        TranslatorUtil.getParamValue(entry, NorthConstants.TEID_PARAM),
                        (short) TranslatorUtil.getParamInt(entry, NorthConstants.TUNNEL_SPORT_PARAM))
                        .setBufferFlag(TranslatorUtil.getParamInt(entry, NorthConstants.BUFFER_FLAG) > 0);
            }
        }
        return farBuilder.build();
    }

    @Override
    public UpfInterface up4EntryToInterface(PiTableEntry entry) throws Up4TranslationException {
        var builder = UpfInterface.builder();
        int srcIfaceTypeInt = TranslatorUtil.getParamInt(entry, NorthConstants.SRC_IFACE_PARAM);
        if (srcIfaceTypeInt == NorthConstants.IFACE_ACCESS) {
            builder.setAccess();
        } else if (srcIfaceTypeInt == NorthConstants.IFACE_CORE) {
            builder.setCore();
        } else {
            throw new Up4TranslationException("Attempting to translate an unsupported UP4 interface type! " +
                    srcIfaceTypeInt);
        }
        Ip4Prefix prefix = TranslatorUtil.getFieldPrefix(entry, NorthConstants.IFACE_DST_PREFIX_KEY);
        builder.setPrefix(prefix);
        return builder.build();
    }

    @Override
    public UpfInterface fabricEntryToInterface(FlowRule entry)
            throws Up4TranslationException {
        Pair<PiCriterion, PiTableAction> matchActionPair = TranslatorUtil.fabricEntryToPiPair(entry);
        PiCriterion match = matchActionPair.getLeft();
        PiAction action = (PiAction) matchActionPair.getRight();

        var ifaceBuilder = UpfInterface.builder()
                .setPrefix(TranslatorUtil.getFieldPrefix(match, SouthConstants.HDR_IPV4_DST_ADDR));

        int interfaceType = TranslatorUtil.getParamInt(action, SouthConstants.SRC_IFACE);
        if (interfaceType == SouthConstants.INTERFACE_ACCESS) {
            ifaceBuilder.setAccess();
        } else if (interfaceType == SouthConstants.INTERFACE_CORE) {
            ifaceBuilder.setCore();
        } else if (interfaceType == SouthConstants.INTERFACE_DBUF) {
            ifaceBuilder.setDbufReceiver();
        }
        return ifaceBuilder.build();
    }


    @Override
    public FlowRule farToFabricEntry(ForwardingActionRule far, DeviceId deviceId, ApplicationId appId, int priority)
            throws Up4TranslationException {
        PiAction action;
        if (!far.encaps()) {
            action = PiAction.builder()
                    .withId(SouthConstants.FABRIC_INGRESS_SPGW_LOAD_NORMAL_FAR)
                    .withParameters(Arrays.asList(
                            new PiActionParam(SouthConstants.DROP, far.drops() ? 1 : 0),
                            new PiActionParam(SouthConstants.NOTIFY_CP, far.notifies() ? 1 : 0)
                    ))
                    .build();

        } else {
            if (far.tunnelSrc() == null || far.tunnelDst() == null
                    || far.teid() == null || far.tunnel().srcPort() == null) {
                throw new Up4TranslationException(
                        "Not all action parameters present when translating " +
                                "intermediate encapsulating/buffering FAR to physical FAR!");
            }
            // TODO: copy tunnel destination port from logical switch write requests, instead of hardcoding 2152
            PiActionId actionId = far.buffers() ? SouthConstants.FABRIC_INGRESS_SPGW_LOAD_DBUF_FAR :
                    SouthConstants.FABRIC_INGRESS_SPGW_LOAD_TUNNEL_FAR;
            action = PiAction.builder()
                    .withId(actionId)
                    .withParameters(Arrays.asList(
                            new PiActionParam(SouthConstants.DROP, far.drops() ? 1 : 0),
                            new PiActionParam(SouthConstants.NOTIFY_CP, far.notifies() ? 1 : 0),
                            new PiActionParam(SouthConstants.TEID, far.teid()),
                            new PiActionParam(SouthConstants.TUNNEL_SRC_ADDR, far.tunnelSrc().toInt()),
                            new PiActionParam(SouthConstants.TUNNEL_DST_ADDR, far.tunnelDst().toInt()),
                            new PiActionParam(SouthConstants.TUNNEL_SRC_PORT, far.tunnel().srcPort())
                    ))
                    .build();
        }
        PiCriterion match = PiCriterion.builder()
                .matchExact(SouthConstants.HDR_FAR_ID, globalFarIdOf(far.sessionId(), far.farId()))
                .build();
        return DefaultFlowRule.builder()
                .forDevice(deviceId).fromApp(appId).makePermanent()
                .forTable(SouthConstants.FABRIC_INGRESS_SPGW_FARS)
                .withSelector(DefaultTrafficSelector.builder().matchPi(match).build())
                .withTreatment(DefaultTrafficTreatment.builder().piTableAction(action).build())
                .withPriority(priority)
                .build();
    }

    @Override
    public FlowRule pdrToFabricEntry(PacketDetectionRule pdr, DeviceId deviceId, ApplicationId appId, int priority)
            throws Up4TranslationException {
        PiCriterion match;
        PiTableId tableId;
        if (pdr.matchesEncapped()) {
            match = PiCriterion.builder()
                    .matchExact(SouthConstants.HDR_TEID, pdr.teid().asArray())
                    .matchExact(SouthConstants.HDR_TUNNEL_IPV4_DST, pdr.tunnelDest().toInt())
                    .build();
            tableId = SouthConstants.FABRIC_INGRESS_SPGW_UPLINK_PDRS;
        } else if (pdr.matchesUnencapped()) {
            match = PiCriterion.builder()
                    .matchExact(SouthConstants.HDR_UE_ADDR, pdr.ueAddress().toInt())
                    .build();
            tableId = SouthConstants.FABRIC_INGRESS_SPGW_DOWNLINK_PDRS;
        } else {
            throw new Up4TranslationException("Flexible PDRs not yet supported! Cannot translate " + pdr.toString());
        }

        PiAction action = PiAction.builder()
                .withId(SouthConstants.FABRIC_INGRESS_SPGW_LOAD_PDR)
                .withParameters(Arrays.asList(
                        new PiActionParam(SouthConstants.CTR_ID, pdr.counterId()),
                        new PiActionParam(SouthConstants.FAR_ID, globalFarIdOf(pdr.sessionId(), pdr.farId())),
                        new PiActionParam(SouthConstants.NEEDS_GTPU_DECAP, pdr.matchesEncapped() ? 1 : 0)
                ))
                .build();

        return DefaultFlowRule.builder()
                .forDevice(deviceId).fromApp(appId).makePermanent()
                .forTable(tableId)
                .withSelector(DefaultTrafficSelector.builder().matchPi(match).build())
                .withTreatment(DefaultTrafficTreatment.builder().piTableAction(action).build())
                .withPriority(priority)
                .build();
    }

    @Override
    public FlowRule interfaceToFabricEntry(UpfInterface upfInterface, DeviceId deviceId,
                                           ApplicationId appId, int priority) throws Up4TranslationException {
        int interfaceTypeInt;
        int gtpuValidity;
        if (upfInterface.isDbufReceiver()) {
            interfaceTypeInt = SouthConstants.INTERFACE_DBUF;
            gtpuValidity = 1;
        } else if (upfInterface.isAccess()) {
            interfaceTypeInt = SouthConstants.INTERFACE_ACCESS;
            gtpuValidity = 1;
        } else {
            interfaceTypeInt = SouthConstants.INTERFACE_CORE;
            gtpuValidity = 0;
        }

        PiCriterion match = PiCriterion.builder()
                .matchLpm(SouthConstants.HDR_IPV4_DST_ADDR,
                        upfInterface.prefix().address().toInt(),
                        upfInterface.prefix().prefixLength())
                .matchExact(SouthConstants.HDR_GTPU_IS_VALID, gtpuValidity)
                .build();
        PiAction action = PiAction.builder()
                .withId(SouthConstants.FABRIC_INGRESS_SPGW_LOAD_IFACE)
                .withParameter(new PiActionParam(SouthConstants.SRC_IFACE, interfaceTypeInt))
                .build();
        return DefaultFlowRule.builder()
                .forDevice(deviceId).fromApp(appId).makePermanent()
                .forTable(SouthConstants.FABRIC_INGRESS_SPGW_INTERFACES)
                .withSelector(DefaultTrafficSelector.builder().matchPi(match).build())
                .withTreatment(DefaultTrafficTreatment.builder().piTableAction(action).build())
                .withPriority(priority)
                .build();
    }

    @Override
    public PiTableEntry farToUp4Entry(ForwardingActionRule far) throws Up4TranslationException {
        PiMatchKey matchKey;
        PiAction action;
        ImmutableByteSequence zeroByte = ImmutableByteSequence.ofZeros(1);
        ImmutableByteSequence oneByte = ImmutableByteSequence.ofOnes(1);
        if (!far.encaps()) {
            action = PiAction.builder()
                    .withId(NorthConstants.LOAD_FAR_NORMAL)
                    .withParameters(Arrays.asList(
                            new PiActionParam(NorthConstants.DROP_FLAG, far.drops() ? oneByte : zeroByte),
                            new PiActionParam(NorthConstants.NOTIFY_FLAG, far.notifies() ? oneByte : zeroByte)
                    ))
                    .build();
        } else {
            if (far.tunnelSrc() == null || far.tunnelDst() == null
                    || far.teid() == null || far.tunnel().srcPort() == null) {
                throw new Up4TranslationException(
                        "Not all action parameters present when translating intermediate encap FAR to logical FAR!");
            }
            action = PiAction.builder()
                    .withId(NorthConstants.LOAD_FAR_TUNNEL)
                    .withParameters(Arrays.asList(
                            new PiActionParam(NorthConstants.DROP_FLAG, far.drops() ? oneByte : zeroByte),
                            new PiActionParam(NorthConstants.NOTIFY_FLAG, far.notifies() ? oneByte : zeroByte),
                            new PiActionParam(NorthConstants.BUFFER_FLAG, far.buffers() ? oneByte : zeroByte),
                            new PiActionParam(NorthConstants.TUNNEL_TYPE_PARAM,
                                    toImmutableByte(NorthConstants.TUNNEL_TYPE_GTPU)),
                            new PiActionParam(NorthConstants.TUNNEL_SRC_PARAM, far.tunnelSrc().toInt()),
                            new PiActionParam(NorthConstants.TUNNEL_DST_PARAM, far.tunnelDst().toInt()),
                            new PiActionParam(NorthConstants.TEID_PARAM, far.teid()),
                            new PiActionParam(NorthConstants.TUNNEL_SPORT_PARAM, far.tunnel().srcPort())
                    ))
                    .build();
        }
        matchKey = PiMatchKey.builder()
                .addFieldMatch(new PiExactFieldMatch(NorthConstants.FAR_ID_KEY,
                        ImmutableByteSequence.copyFrom(far.farId())))
                .addFieldMatch(new PiExactFieldMatch(NorthConstants.SESSION_ID_KEY, far.sessionId()))
                .build();

        return PiTableEntry.builder()
                .forTable(NorthConstants.FAR_TBL)
                .withMatchKey(matchKey)
                .withAction(action)
                .build();
    }

    @Override
    public PiTableEntry pdrToUp4Entry(PacketDetectionRule pdr) throws Up4TranslationException {
        PiMatchKey matchKey;
        PiAction action;
        int decapFlag;
        if (pdr.matchesEncapped()) {
            decapFlag = 1;
            matchKey = PiMatchKey.builder()
                    .addFieldMatch(new PiExactFieldMatch(NorthConstants.SRC_IFACE_KEY,
                            toImmutableByte(NorthConstants.IFACE_ACCESS)))
                    .addFieldMatch(new PiTernaryFieldMatch(NorthConstants.TEID_KEY, pdr.teid(), allOnes32))
                    .addFieldMatch(new PiTernaryFieldMatch(NorthConstants.TUNNEL_DST_KEY,
                            ImmutableByteSequence.copyFrom(pdr.tunnelDest().toOctets()), allOnes32))
                    .build();
        } else {
            decapFlag = 0;
            matchKey = PiMatchKey.builder()
                    .addFieldMatch(new PiExactFieldMatch(NorthConstants.SRC_IFACE_KEY,
                            toImmutableByte(NorthConstants.IFACE_CORE)))
                    .addFieldMatch(new PiTernaryFieldMatch(NorthConstants.UE_ADDR_KEY,
                            ImmutableByteSequence.copyFrom(pdr.ueAddress().toOctets()), allOnes32))
                    .build();
        }
        // FIXME: pdr_id is not yet stored on writes so it cannot be read
        action = PiAction.builder()
                .withId(NorthConstants.LOAD_PDR)
                .withParameters(Arrays.asList(
                        new PiActionParam(NorthConstants.PDR_ID_PARAM, 0),
                        new PiActionParam(NorthConstants.SESSION_ID_PARAM, pdr.sessionId()),
                        new PiActionParam(NorthConstants.CTR_ID, pdr.counterId()),
                        new PiActionParam(NorthConstants.FAR_ID_PARAM, pdr.farId()),
                        new PiActionParam(NorthConstants.DECAP_FLAG_PARAM, toImmutableByte(decapFlag))
                ))
                .build();

        return PiTableEntry.builder()
                .forTable(NorthConstants.PDR_TBL)
                .withMatchKey(matchKey)
                .withAction(action)
                .build();
    }

    private ImmutableByteSequence toImmutableByte(int value) {
        try {
            return ImmutableByteSequence.copyFrom(value).fit(8);
        } catch (ImmutableByteSequence.ByteSequenceTrimException e) {
            log.error("Attempted to convert an integer larger than 255 to a byte!: {}", e.getMessage());
            return ImmutableByteSequence.ofZeros(1);
        }
    }

    @Override
    public PiTableEntry interfaceToUp4Entry(UpfInterface upfInterface) throws Up4TranslationException {
        int srcIface = upfInterface.isAccess() ? NorthConstants.IFACE_ACCESS : NorthConstants.IFACE_CORE;
        int direction = upfInterface.isAccess() ? NorthConstants.DIRECTION_UPLINK : NorthConstants.DIRECTION_DOWNLINK;
        return PiTableEntry.builder()
                .forTable(NorthConstants.IFACE_TBL)
                .withMatchKey(PiMatchKey.builder()
                        .addFieldMatch(new PiLpmFieldMatch(
                                NorthConstants.IFACE_DST_PREFIX_KEY,
                                ImmutableByteSequence.copyFrom(upfInterface.prefix().address().toOctets()),
                                upfInterface.prefix().prefixLength()))
                        .build())
                .withAction(PiAction.builder()
                        .withId(NorthConstants.LOAD_IFACE)
                        .withParameters(Arrays.asList(
                                new PiActionParam(NorthConstants.SRC_IFACE_PARAM, toImmutableByte(srcIface)),
                                new PiActionParam(NorthConstants.DIRECTION, toImmutableByte(direction))
                        ))
                        .build()).build();
    }

    // NOTE: FarIdMapListener is run on the same thread intentionally in order to ensure that
    //       reverseFarIdMap update always finishes right after farIdMap is updated
    private class FarIdMapListener implements MapEventListener<UpfRuleIdentifier, Integer> {
        @Override
        public void event(MapEvent<UpfRuleIdentifier, Integer> event) {
            switch (event.type()) {
                case INSERT:
                    reverseFarIdMap.put(event.newValue().value(), event.key());
                    break;
                case UPDATE:
                    reverseFarIdMap.remove(event.oldValue().value());
                    reverseFarIdMap.put(event.newValue().value(), event.key());
                    break;
                case REMOVE:
                    reverseFarIdMap.remove(event.oldValue().value());
                    break;
                default:
                    break;
            }
        }
    }
}
