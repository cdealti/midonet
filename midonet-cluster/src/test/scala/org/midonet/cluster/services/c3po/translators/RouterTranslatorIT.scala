/*
 * Copyright 2015 Midokura SARL
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
package org.midonet.cluster.services.c3po.translators

import java.util.UUID

import org.junit.runner.RunWith
import org.midonet.cluster.C3POMinionTestBase
import org.midonet.cluster.data.neutron.NeutronResourceType.{Pool => PoolType, Port => PortType, Router => RouterType, Subnet => SubnetType}
import org.midonet.cluster.data.storage.StateTable
import org.midonet.cluster.models.Commons
import org.midonet.cluster.models.Commons.Condition.FragmentPolicy
import org.midonet.cluster.models.Neutron.NeutronRoute
import org.midonet.cluster.models.Topology.Route.NextHop
import org.midonet.cluster.models.Topology.Rule.Action
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.services.c3po.translators.RouterTranslator.tenantGwPortId
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.cluster.util.{IPAddressUtil, IPSubnetUtil, UUIDUtil}
import org.midonet.packets.util.AddressConversions._
import org.midonet.packets.{IPv4Addr, ICMP, MAC}
import org.midonet.util.concurrent.toFutureOps
import org.scalatest.junit.JUnitRunner

import scala.collection.JavaConverters._

@RunWith(classOf[JUnitRunner])
class RouterTranslatorIT extends C3POMinionTestBase {
    /* Set up legacy Data Client for testing Replicated Map. */
    override protected val useLegacyDataClient = true

    "The RouterTranslator" should "handle router CRUD" in {
        val r1Id = UUID.randomUUID()
        val r1Json = routerJson(r1Id, name = "router1")
        insertCreateTask(2, RouterType, r1Json, r1Id)

        val r1 = eventually(storage.get(classOf[Router], r1Id).await())
        UUIDUtil.fromProto(r1.getId) shouldBe r1Id
        r1.getName shouldBe "router1"
        r1.getAdminStateUp shouldBe true
        r1.getInboundFilterId should not be null
        r1.getOutboundFilterId should not be null
        r1.getForwardChainId should not be null

        val r1Chains = getChains(r1.getInboundFilterId, r1.getOutboundFilterId)
        r1Chains.inChain.getRuleIdsCount shouldBe 0
        r1Chains.outChain.getRuleIdsCount shouldBe 0
        val r1FwdChain = storage.get(classOf[Chain],
                                     r1.getForwardChainId).await()
        r1FwdChain.getRuleIdsCount shouldBe 0

        val pgId = PortManager.portGroupId(r1.getId)
        val pg = eventually(storage.get(classOf[PortGroup], pgId).await())
        pg.getName shouldBe RouterTranslator.portGroupName(pgId)
        pg.getTenantId shouldBe r1.getTenantId
        pg.getStateful shouldBe true

        val r2Id = UUID.randomUUID()
        val r2Json = routerJson(r2Id, name = "router2", adminStateUp = false)
        val r1JsonV2 = routerJson(r1Id, tenantId = "new-tenant")
        insertCreateTask(3, RouterType, r2Json, r2Id)
        insertUpdateTask(4, RouterType, r1JsonV2, r1Id)

        val r2 = eventually(storage.get(classOf[Router], r2Id).await())
        r2.getName shouldBe "router2"

        eventually {
            val r1v2 = storage.get(classOf[Router], r1Id).await()
            r1v2.getTenantId shouldBe "new-tenant"

            // Chains should be preserved.
            r1v2.getInboundFilterId shouldBe r1.getInboundFilterId
            r1v2.getOutboundFilterId shouldBe r1.getOutboundFilterId
            r1v2.getForwardChainId shouldBe r1.getForwardChainId
        }

        insertDeleteTask(5, RouterType, r1Id)
        insertDeleteTask(6, RouterType, r2Id)

        eventually {
            storage.getAll(classOf[Router]).await().size shouldBe 0
        }
    }

    it should "handle extra routes CRUD" in {

        // Create external network.
        val extNwId = createTenantNetwork(2, external = true)
        val extNwSubnetId = createSubnet(3, extNwId, "200.0.0.0/24",
                                         gatewayIp = "200.0.0.1")

        // Create a tenant network
        val nwId = createTenantNetwork(4, external = false)
        val subnetId = createSubnet(5, nwId, "192.168.1.0/24",
                                    gatewayIp = "192.168.1.1")

        // Create a router with gateway
        val routerId = UUID.randomUUID()
        val extNwGwPortId = UUID.randomUUID()
        createRouterGatewayPort(6, extNwGwPortId, extNwId, routerId,
                                "200.0.0.2", "ab:cd:ef:00:00:03",
                                extNwSubnetId)
        createRouter(7, routerId, gwPortId = extNwGwPortId, enableSnat = true)

        // Create a router interface
        val rtrIfId = UUID.randomUUID()
        createRouterInterfacePort(8, rtrIfId, nwId, routerId, "192.168.1.2",
                                  "02:02:02:02:02:02", subnetId)
        createRouterInterface(9, routerId, rtrIfId, subnetId)

        // Update with extra routes
        val route1 = NeutronRoute.newBuilder()
            .setDestination(IPSubnetUtil.toProto("10.0.0.0/24"))
            .setNexthop(IPAddressUtil.toProto("200.0.0.3")).build
        val route2 = NeutronRoute.newBuilder()
            .setDestination(IPSubnetUtil.toProto("10.0.1.0/24"))
            .setNexthop(IPAddressUtil.toProto("192.168.1.3")).build
        var rtrWithRoutes = routerJson(routerId, gwPortId = extNwGwPortId,
                                       enableSnat = true,
                                       routes = List(route1, route2))
        insertUpdateTask(10, RouterType, rtrWithRoutes, routerId)

        eventually {
            validateExtraRoute(routerId, route1,
                               RouterTranslator.tenantGwPortId(extNwGwPortId))
            validateExtraRoute(routerId, route2,
                               PortManager.routerInterfacePortPeerId(rtrIfId))
        }

        // Replace a route (add/remove)
        val route3 = NeutronRoute.newBuilder()
            .setDestination(IPSubnetUtil.toProto("10.0.2.0/24"))
            .setNexthop(IPAddressUtil.toProto("200.0.0.4")).build
        rtrWithRoutes = routerJson(routerId, gwPortId = extNwGwPortId,
                                   enableSnat = true,
                                   routes = List(route1, route3))
        insertUpdateTask(11, RouterType, rtrWithRoutes, routerId)

        eventually {
            validateExtraRoute(routerId, route1,
                               RouterTranslator.tenantGwPortId(extNwGwPortId))
            validateExtraRoute(routerId, route3,
                               RouterTranslator.tenantGwPortId(extNwGwPortId))

            // Validate that route2 is gone
            val rtId = RouteManager.extraRouteId(routerId, route2)
            storage.exists(classOf[Route], rtId).await() shouldBe false
        }
    }

    it should "handle router gateway CRUD" in {
        val uplNwDhcpPortId = UUID.randomUUID()

        val extNwDhcpPortId = UUID.randomUUID()

        val hostId = UUID.randomUUID()

        val edgeRtrId = UUID.randomUUID()
        val edgeRtrUplNwIfPortId = UUID.randomUUID()
        val edgeRtrExtNwIfPortId = UUID.randomUUID()

        val tntRtrId = UUID.randomUUID()
        val extNwGwPortId = UUID.randomUUID()

        // Create uplink network.
        val uplNetworkId = createUplinkNetwork(2)
        val uplNwSubnetId = createSubnet(3, uplNetworkId, "10.0.0.0/16")
        createDhcpPort(4, uplNwDhcpPortId, uplNetworkId,
                       uplNwSubnetId, "10.0.0.1")

        createHost(hostId)

        // Create edge router.
        createRouter(5, edgeRtrId)
        createRouterInterfacePort(6, edgeRtrUplNwIfPortId, uplNetworkId,
                                  edgeRtrId, "10.0.0.2", "02:02:02:02:02:02",
                                  uplNwSubnetId, hostId = hostId,
                                  ifName = "eth0")
        createRouterInterface(7, edgeRtrId, edgeRtrUplNwIfPortId, uplNwSubnetId)

        // Create external network.
        val extNwId = createTenantNetwork(8, external = true)
        val extNwSubnetId = createSubnet(9, extNwId, "10.0.1.0/24",
                                         gatewayIp = "10.0.1.2")
        createDhcpPort(10, extNwDhcpPortId, extNwId,
                       extNwSubnetId, "10.0.1.0")
        createRouterInterfacePort(11, edgeRtrExtNwIfPortId, extNwId, edgeRtrId,
                                  "10.0.1.2", "03:03:03:03:03:03",
                                  extNwSubnetId)
        createRouterInterface(12, edgeRtrId,
                              edgeRtrExtNwIfPortId, extNwSubnetId)
        val extNwArpTable = stateTableStorage.bridgeArpTable(extNwId)

        // Sanity check for external network's connection to edge router. This
        // is just a normal router interface, so RouterInterfaceTranslatorIT
        // checks the details.
        eventually {
            val nwPort = storage.get(classOf[Port],
                                     edgeRtrExtNwIfPortId).await()
            val rPortF = storage.get(classOf[Port], nwPort.getPeerId)
            val rtrF = storage.get(classOf[Router], edgeRtrId)
            val (rPort, rtr) = (rPortF.await(), rtrF.await())
            rPort.getRouterId shouldBe rtr.getId
            rPort.getPortAddress.getAddress shouldBe "10.0.1.2"
            rPort.getPortMac shouldBe "03:03:03:03:03:03"
            rtr.getPortIdsCount shouldBe 2
            rtr.getPortIdsList.asScala should contain(rPort.getId)
            // Start the replicated map here.
            extNwArpTable.start()
        }

        // Create tenant router and check gateway setup.
        createRouterGatewayPort(13, extNwGwPortId, extNwId, tntRtrId,
                                "10.0.1.3", "ab:cd:ef:00:00:03", extNwSubnetId)

        createRouter(14, tntRtrId, gwPortId = extNwGwPortId, enableSnat = true)
        validateGateway(tntRtrId, extNwGwPortId, "10.0.1.0/24", "10.0.1.3",
                        "ab:cd:ef:00:00:03", "10.0.1.2", snatEnabled = true,
                        extNwArpTable)

        // Rename router to make sure update doesn't break anything.
        val trRenamedJson = routerJson(tntRtrId, name = "tr-renamed",
                                       gwPortId = extNwGwPortId,
                                       enableSnat = true)
        insertUpdateTask(15, RouterType, trRenamedJson, tntRtrId)
        eventually {
            val tr = storage.get(classOf[Router], tntRtrId).await()
            tr.getName shouldBe "tr-renamed"
            tr.getPortIdsCount shouldBe 1
            validateGateway(tntRtrId, extNwGwPortId, "10.0.1.0/24", "10.0.1.3",
                            "ab:cd:ef:00:00:03", "10.0.1.2", snatEnabled = true,
                            extNwArpTable)
        }

        // Delete gateway.
        insertDeleteTask(16, PortType, extNwGwPortId)
        eventually {
            val trF = storage.get(classOf[Router], tntRtrId)
            val extNwF = storage.get(classOf[Network], extNwId)
            val (tr, extNw) = (trF.await(), extNwF.await())
            tr.getPortIdsCount shouldBe 0
            extNw.getPortIdsList.asScala should contain only (
                UUIDUtil.toProto(edgeRtrExtNwIfPortId),
                UUIDUtil.toProto(extNwDhcpPortId))
            extNwArpTable.containsLocal(IPv4Addr("10.0.1.3")) shouldBe false
            validateNatRulesDeleted(tntRtrId)
        }

        // Re-add gateway.
        createRouterGatewayPort(17, extNwGwPortId, extNwId, tntRtrId,
                                "10.0.1.4", "ab:cd:ef:00:00:04", extNwSubnetId)
        val trAddGwJson = routerJson(tntRtrId, name = "tr-add-gw",
                                     gwPortId = extNwGwPortId,
                                     enableSnat = true)
        insertUpdateTask(18, RouterType, trAddGwJson, tntRtrId)
        eventually(validateGateway(tntRtrId, extNwGwPortId, "10.0.1.0/24",
                                   "10.0.1.4", "ab:cd:ef:00:00:04", "10.0.1.2",
                                   snatEnabled = true, extNwArpTable))

        // Disable SNAT.
        val trDisableSnatJson = routerJson(tntRtrId, name = "tr-disable-snat",
                                           gwPortId = extNwGwPortId,
                                           enableSnat = false)
        insertUpdateTask(19, RouterType, trDisableSnatJson, tntRtrId)
        eventually(validateGateway(tntRtrId, extNwGwPortId, "10.0.1.0/24",
                                   "10.0.1.4", "ab:cd:ef:00:00:04", "10.0.1.2",
                                   snatEnabled = false, extNwArpTable))

        // Re-enable SNAT.
        insertUpdateTask(20, RouterType, trAddGwJson, tntRtrId)
        eventually(validateGateway(tntRtrId, extNwGwPortId, "10.0.1.0/24",
                                   "10.0.1.4", "ab:cd:ef:00:00:04", "10.0.1.2",
                                   snatEnabled = true, extNwArpTable))

        // Clear the default gateway on the subnet.
        val extNwSubnetNoGwJson =
            subnetJson(extNwSubnetId, extNwId, cidr = "10.0.1.0/24")
        insertUpdateTask(21, SubnetType, extNwSubnetNoGwJson, extNwSubnetId)
        eventually(validateGateway(tntRtrId, extNwGwPortId, "10.0.1.0/24",
                                   "10.0.1.4", "ab:cd:ef:00:00:04", null,
                                   snatEnabled = true, extNwArpTable))

        // Readd the default gateway on the subnet.
        val extNwSubnetNewGwJson =
            subnetJson(extNwSubnetId, extNwId, cidr = "10.0.1.0/24",
                       gatewayIp = "10.0.1.50")
        insertUpdateTask(22, SubnetType, extNwSubnetNewGwJson, extNwSubnetId)
        eventually(validateGateway(tntRtrId, extNwGwPortId, "10.0.1.0/24",
                                   "10.0.1.4", "ab:cd:ef:00:00:04",
                                   "10.0.1.50", snatEnabled = true,
                                   extNwArpTable))

        // Delete gateway and router.
        insertDeleteTask(23, PortType, extNwGwPortId)
        insertDeleteTask(24, RouterType, tntRtrId)
        eventually {
            val extNwF = storage.get(classOf[Network], extNwId)
            List(storage.exists(classOf[Router], tntRtrId),
                 storage.exists(classOf[Port], extNwGwPortId),
                 storage.exists(classOf[Port], tenantGwPortId(extNwGwPortId)))
                .map(_.await()) shouldBe List(false, false, false)
            val extNw = extNwF.await()
            extNw.getPortIdsList.asScala should contain only (
                UUIDUtil.toProto(edgeRtrExtNwIfPortId),
                UUIDUtil.toProto(extNwDhcpPortId))
            extNwArpTable.containsLocal(IPv4Addr("10.0.1.4")) shouldBe false
        }
        extNwArpTable.stop()
    }

    it should "Preserve router properties on update" in {
        // Create external network and subnet.
        val nwId = createTenantNetwork(10, external = true)
        val snId = createSubnet(20, nwId, "10.0.1.0/24", gatewayIp = "10.0.1.1")

        // Create router with gateway port.
        val rtrId = UUID.randomUUID()
        val gwPortId = UUID.randomUUID()
        createRouterGatewayPort(30, gwPortId, nwId, rtrId, "10.0.1.2",
                                "ab:ab:ab:ab:ab:ab", snId)
        createRouter(40, rtrId, gwPortId)

        // Create pool to give router load balancer ID.
        val lbPoolId = UUID.randomUUID()
        val lbPoolJson = poolJson(lbPoolId, rtrId)
        insertCreateTask(50, PoolType, lbPoolJson, lbPoolId)

        // Make sure everything was created.
        val rtr = eventually {
            val rtr = storage.get(classOf[Router], rtrId).await()
            rtr.hasLoadBalancerId shouldBe true
            rtr
        }

        // Add a route.
        val routeId = UUID.randomUUID()
        storage.create(Route.newBuilder
                           .setId(toProto(routeId))
                           .setRouterId(toProto(rtrId))
                           .build())

        // Add mirrors.
        val inMirrorId = UUID.randomUUID()
        storage.create(Mirror.newBuilder
                           .setId(toProto(inMirrorId))
                           .addRouterInboundIds(toProto(rtrId))
                           .build())
        val outMirrorId = UUID.randomUUID()
        storage.create(Mirror.newBuilder
                           .setId(toProto(outMirrorId))
                           .addRouterOutboundIds(toProto(rtrId))
                           .build())

        // Add BGP network and peer.
        val bgpNwId = UUID.randomUUID()
        storage.create(BgpNetwork.newBuilder
                           .setId(toProto(bgpNwId))
                           .setRouterId(toProto(rtrId))
                           .build())
        val bgpPeerId = UUID.randomUUID()
        storage.create(BgpPeer.newBuilder
                           .setId(toProto(bgpPeerId))
                           .setRouterId(toProto(rtrId))
                           .build())

        // Add trace request.
        val trqId = UUID.randomUUID()
        storage.create(TraceRequest.newBuilder
                           .setId(toProto(trqId))
                           .setRouterId(toProto(rtrId))
                           .build())

        // Now update the router and make sure the references are preserved.
        val rtrJsonV2 = routerJson(rtrId, name = "routerV2",
                                   gwPortId = gwPortId)
        insertUpdateTask(60, RouterType, rtrJsonV2, rtrId)
        eventually {
            val rtrV2 = storage.get(classOf[Router], rtrId).await()
            rtrV2.getId shouldBe toProto(rtrId)
            rtrV2.getName shouldBe "routerV2"
            rtrV2.getInboundFilterId shouldBe rtr.getInboundFilterId
            rtrV2.getOutboundFilterId shouldBe rtr.getOutboundFilterId
            rtrV2.getForwardChainId shouldBe rtr.getForwardChainId
            rtrV2.getLoadBalancerId shouldBe rtr.getLoadBalancerId
            rtrV2.getRouteIdsList should contain only toProto(routeId)
            rtrV2.getBgpNetworkIdsList should contain only toProto(bgpNwId)
            rtrV2.getBgpPeerIdsList should contain only toProto(bgpPeerId)
            rtrV2.getInboundMirrorIdsList should contain only toProto(inMirrorId)
            rtrV2.getOutboundMirrorIdsList should contain only toProto(outMirrorId)
            rtrV2.getPortIdsList shouldBe rtr.getPortIdsList
            rtrV2.getTraceRequestIdsList should contain only toProto(trqId)
        }

    }

    private def validateExtraRoute(rtrId: UUID,
                                   rt: NeutronRoute,
                                   nextHopPortId: UUID): Unit = {
        val rtId = RouteManager.extraRouteId(rtrId, rt)
        val route = storage.get(classOf[Route], rtId).await()
        route.getNextHopPortId shouldBe UUIDUtil.toProto(nextHopPortId)
        route.getDstSubnet shouldBe rt.getDestination
        route.getNextHopGateway shouldBe rt.getNexthop
    }

    private def validateGateway(rtrId: UUID, nwGwPortId: UUID,
                                extSubnetCidr: String, gatewayIp: String,
                                trPortMac: String, nextHopIp: String,
                                snatEnabled: Boolean,
                                extNwArpTable: StateTable[IPv4Addr, MAC])
    : Unit = {
        // Tenant router should have gateway port and no routes.
        val trGwPortId = tenantGwPortId(nwGwPortId)
        val tr = eventually {
            val tr = storage.get(classOf[Router], rtrId).await()
            tr.getPortIdsList.asScala should contain only trGwPortId
            tr.getRouteIdsCount shouldBe 0

            // The ARP entry should be added to the external network ARP table.
            extNwArpTable.getLocal(gatewayIp) shouldBe MAC.fromString(trPortMac)
            tr
        }

        // Get the router gateway port and its peer on the network.
        val portFs = storage.getAll(classOf[Port], List(nwGwPortId, trGwPortId))

        // Get routes on router gateway port.
        val trRifRtId = RouteManager.routerInterfaceRouteId(trGwPortId)
        val trLocalRtId = RouteManager.localRouteId(trGwPortId)
        val trGwRtId = RouteManager.gatewayRouteId(trGwPortId)

        val List(trLocalRt, trGwRt) =
            List(trLocalRtId, trGwRtId)
                .map(storage.get(classOf[Route], _)).map(_.await())

        val List(nwGwPort, trGwPort) = portFs.await()

        // Check router port has correct router and route IDs.  This also
        // validates that there is no network route created for the gw port.
        trGwPort.getRouterId shouldBe UUIDUtil.toProto(rtrId)
        trGwPort.getRouteIdsList.asScala should
            contain only (trGwRtId, trRifRtId, trLocalRtId)

        // Network port has no routes.
        nwGwPort.getRouteIdsCount shouldBe 0

        // Ports should be linked.
        nwGwPort.getPeerId shouldBe trGwPortId
        trGwPort.getPeerId shouldBe nwGwPort.getId

        trGwPort.getPortAddress.getAddress shouldBe gatewayIp
        trGwPort.getPortMac shouldBe trPortMac

        validateLocalRoute(trLocalRt, trGwPort)

        trGwRt.getNextHop shouldBe NextHop.PORT
        trGwRt.getNextHopPortId shouldBe trGwPort.getId
        trGwRt.getDstSubnet shouldBe IPSubnetUtil.univSubnet4
        trGwRt.getSrcSubnet shouldBe IPSubnetUtil.univSubnet4
        if (nextHopIp == null) trGwRt.hasNextHopGateway shouldBe false
        else trGwRt.getNextHopGateway.getAddress shouldBe nextHopIp

        if (snatEnabled)
            validateGatewayNatRules(tr, gatewayIp, trGwPortId)
    }

    private def validateLocalRoute(rt: Route, nextHopPort: Port): Unit = {
        rt.getNextHop shouldBe NextHop.LOCAL
        rt.getNextHopPortId shouldBe nextHopPort.getId
        rt.getDstSubnet shouldBe
        IPSubnetUtil.fromAddr(nextHopPort.getPortAddress)
        rt.getSrcSubnet shouldBe IPSubnetUtil.univSubnet4
        rt.getWeight shouldBe RouteManager.DEFAULT_WEIGHT
    }

    private def validateGatewayNatRules(tr: Router, gatewayIp: String,
                                        trGwPortId: Commons.UUID): Unit = {
        val chainIds = List(tr.getInboundFilterId, tr.getOutboundFilterId)
        val List(inChain, outChain) =
            storage.getAll(classOf[Chain], chainIds).await()
        inChain.getRuleIdsCount shouldBe 2
        outChain.getRuleIdsCount shouldBe 2

        val ruleIds = inChain.getRuleIdsList.asScala.toList ++
                      outChain.getRuleIdsList.asScala
        val List(inRevSnatRule, inDropWrongPortTrafficRule,
                 outSnatRule, outDropFragmentsRule) =
            storage.getAll(classOf[Rule], ruleIds).await()

        val gwSubnet = IPSubnetUtil.fromAddr(gatewayIp)

        outSnatRule.getChainId shouldBe outChain.getId
        outSnatRule.getCondition.getOutPortIdsList.asScala should contain only trGwPortId
        outSnatRule.getCondition.getNwSrcIp shouldBe gwSubnet
        outSnatRule.getCondition.getNwSrcInv shouldBe true
        validateNatRule(outSnatRule, dnat = false, gatewayIp)

        val odfr = outDropFragmentsRule
        odfr.getChainId shouldBe outChain.getId
        odfr.getCondition.getOutPortIdsList should contain only trGwPortId
        odfr.getCondition.getNwSrcIp.getAddress shouldBe gatewayIp
        odfr.getCondition.getNwSrcInv shouldBe true
        odfr.getType shouldBe Rule.Type.LITERAL_RULE
        odfr.getCondition.getFragmentPolicy shouldBe FragmentPolicy.ANY
        odfr.getAction shouldBe Action.DROP

        inRevSnatRule.getChainId shouldBe inChain.getId
        inRevSnatRule.getCondition.getNwDstIp shouldBe gwSubnet
        validateNatRule(inRevSnatRule, dnat = false, addr = null)

        val idwptr = inDropWrongPortTrafficRule
        idwptr.getChainId shouldBe inChain.getId
        idwptr.getCondition.getNwDstIp shouldBe gwSubnet
        idwptr.getType shouldBe Rule.Type.LITERAL_RULE
        idwptr.getCondition.getNwProto shouldBe ICMP.PROTOCOL_NUMBER
        idwptr.getAction shouldBe Action.DROP
    }

    // addr == null for reverse NAT rule
    private def validateNatRule(r: Rule, dnat: Boolean, addr: String): Unit = {
        r.getType shouldBe Rule.Type.NAT_RULE
        r.getAction shouldBe Rule.Action.ACCEPT

        val data = r.getNatRuleData
        data.getDnat shouldBe dnat
        data.getReverse shouldBe (addr == null)

        if (addr != null) {
            data.getNatTargetsCount shouldBe 1
            data.getNatTargets(0).getTpStart shouldBe 1
            data.getNatTargets(0).getTpEnd shouldBe 0xffff
            data.getNatTargets(0).getNwStart.getAddress shouldBe addr
            data.getNatTargets(0).getNwEnd.getAddress shouldBe addr
        }
    }

    private def validateNatRulesDeleted(rtrId: UUID): Unit = {
        import RouterTranslator._
        val r = storage.get(classOf[Router], rtrId).await()
        val Seq(inChain, outChain) = storage.getAll(
            classOf[Chain],
            Seq(r.getInboundFilterId, r.getOutboundFilterId)).await()
        inChain.getRuleIdsCount shouldBe 0
        outChain.getRuleIdsCount shouldBe 0
        Seq(outSnatRuleId(rtrId), outDropUnmatchedFragmentsRuleId(rtrId),
            inReverseSnatRuleId(rtrId), inDropWrongPortTrafficRuleId(rtrId))
            .map(storage.exists(classOf[Rule], _).await()) shouldBe
            Seq(false, false, false, false)
    }
}
