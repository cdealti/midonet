/*
* Copyright 2012 Midokura 
*/
package com.midokura.midolman

import scala.Some
import scala.collection.JavaConversions._

import collection.mutable
import akka.testkit.TestProbe
import akka.util.duration._
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.slf4j.LoggerFactory
import guice.actors.OutgoingMessage

import com.midokura.midolman.FlowController.{WildcardFlowRemoved,
                                             WildcardFlowAdded, DiscardPacket}
import layer3.Route
import layer3.Route.NextHop
import topology.LocalPortActive
import com.midokura.packets._
import com.midokura.midonet.cluster.data.dhcp.Opt121
import com.midokura.midonet.cluster.data.dhcp.Subnet
import com.midokura.midonet.cluster.data.{Bridge => ClusterBridge}
import topology.VirtualToPhysicalMapper.HostRequest
import util.SimulationHelper
import com.midokura.midonet.cluster.data.ports.MaterializedBridgePort
import com.midokura.sdn.dp.flows.{FlowActionOutput, FlowAction}
import util.RouterHelper
import com.midokura.midolman.SimulationController.EmitGeneratedPacket
import com.midokura.midolman.DatapathController.PacketIn

@RunWith(classOf[JUnitRunner])
class PingTestCase extends MidolmanTestCase with
        VirtualConfigurationBuilders with SimulationHelper with RouterHelper {
    private final val log = LoggerFactory.getLogger(classOf[PingTestCase])

    // Router port one connecting to host VM1
    val routerIp1 = IntIPv4.fromString("192.168.111.1", 24)
    val routerMac1 = MAC.fromString("22:aa:aa:ff:ff:ff")
    // Interior router port connecting to bridge
    val routerIp2 = IntIPv4.fromString("192.168.222.1", 24)
    val routerMac2 = MAC.fromString("22:ab:cd:ff:ff:ff")
    // VM1: remote host to ping
    val vm1Ip = IntIPv4.fromString("192.168.111.2", 24)
    // DHCP client
    val vm2IP = IntIPv4.fromString("192.168.222.2", 24)
    val vm2Mac = MAC.fromString("02:DD:AA:DD:AA:03")
    var brPort2 : MaterializedBridgePort = null
    val vm2PortName = "VM2"
    var vm2PortNumber = 0

    var bridge: ClusterBridge = null

    private var flowEventsProbe: TestProbe = null
    private var portEventsProbe: TestProbe = null
    private var packetsEventsProbe: TestProbe = null

    override def beforeTest() {
        flowEventsProbe = newProbe()
        portEventsProbe = newProbe()
        packetsEventsProbe = newProbe()
        actors().eventStream.subscribe(flowEventsProbe.ref, classOf[WildcardFlowAdded])
        actors().eventStream.subscribe(flowEventsProbe.ref, classOf[WildcardFlowRemoved])
        actors().eventStream.subscribe(portEventsProbe.ref, classOf[LocalPortActive])
        actors().eventStream.subscribe(packetsEventsProbe.ref, classOf[PacketsExecute])

        val host = newHost("myself", hostId())
        host should not be null
        val router = newRouter("router")
        router should not be null

        initializeDatapath() should not be (null)
        requestOfType[HostRequest](vtpProbe())
        requestOfType[OutgoingMessage](vtpProbe())

        // set up materialized port on router
        val rtrPort1 = newExteriorRouterPort(router, routerMac1,
            routerIp1.toUnicastString, 
            routerIp1.toNetworkAddress.toUnicastString,
            routerIp1.getMaskLength)
        rtrPort1 should not be null

        newRoute(router, "0.0.0.0", 0,
            routerIp1.toNetworkAddress.toUnicastString, routerIp1.getMaskLength,
            NextHop.PORT, rtrPort1.getId,
            new IntIPv4(Route.NO_GATEWAY).toUnicastString, 10)

        // set up logical port on router
        val rtrPort2 = newInteriorRouterPort(router, routerMac2,
            routerIp2.toUnicastString, 
            routerIp2.toNetworkAddress.toUnicastString,
            routerIp2.getMaskLength)
        rtrPort2 should not be null

        newRoute(router, "0.0.0.0", 0,
            routerIp2.toNetworkAddress.toUnicastString, routerIp2.getMaskLength,
            NextHop.PORT, rtrPort2.getId,
            new IntIPv4(Route.NO_GATEWAY).toUnicastString, 10)

        // create bridge link to router's logical port
        bridge = newBridge("bridge")
        bridge should not be null

        val brPort1 = newInteriorBridgePort(bridge)
        brPort1 should not be null
        clusterDataClient().portsLink(rtrPort2.getId, brPort1.getId)

        // add a materialized port on bridge, logically connect to VM2
        brPort2 = newExteriorBridgePort(bridge)
        brPort2 should not be null
        
        // DHCP related setup
        // set up Option 121
        var opt121Obj = (new Opt121()
                       .setGateway(routerIp2)
                       .setRtDstSubnet(routerIp1.toNetworkAddress))
        var opt121Routes: List[Opt121] = List(opt121Obj)
        // set DHCP subnet
        var dhcpSubnet = (new Subnet()
                       .setSubnetAddr(routerIp2.toNetworkAddress)
                       .setDefaultGateway(routerIp2)
                       .setOpt121Routes(opt121Routes))
        addDhcpSubnet(bridge, dhcpSubnet)
        // set DHCP host
        materializePort(brPort2, host, vm2PortName)
        requestOfType[LocalPortActive](portEventsProbe)
        dpController().underlyingActor.vifToLocalPortNumber(brPort2.getId) match {
            case Some(portNo : Short) => vm2PortNumber = portNo
            case None => fail("Not able to find data port number for bridge port 2")
        }
        var dhcpHost = (new com.midokura.midonet.cluster.data.dhcp.Host()
                           .setMAC(vm2Mac)
                           .setIp(vm2IP))
        addDhcpHost(bridge, dhcpSubnet, dhcpHost)

        flowProbe().expectMsgType[DatapathController.DatapathReady].datapath should not be (null)
        drainProbes()
    }

    private def expectPacketOut(portNum : Int): Ethernet = {
        val pktOut = requestOfType[PacketsExecute](packetsEventsProbe).packet
        pktOut should not be null
        pktOut.getData should not be null
        log.debug("Packet execute: {}", pktOut)

        pktOut.getActions.size should equal (1)

        pktOut.getActions.toList map { action =>
            action.getKey should be === FlowAction.FlowActionAttr.OUTPUT
            action.getValue.getClass() should be === classOf[FlowActionOutput]
            action.getValue.asInstanceOf[FlowActionOutput].getPortNumber
        } should contain (portNum)

        Ethernet.deserialize(pktOut.getData)
    }

    private def arpAndCheckReply(portName: String, srcMac: MAC, srcIp: IntIPv4,
                                 dstIp: IntIPv4, expectedMac: MAC, portNum : Int) {

        injectArpRequest(portName, srcIp.getAddress, srcMac, dstIp.getAddress)
        val pkt = expectPacketOut(portNum)
        log.debug("Packet out: {}", pkt)
        // TODO(guillermo) check the arp reply packet
    }

    private def injectIcmpEcho(portName : String, srcMac : MAC, srcIp : IntIPv4,
                               dstMac : MAC, dstIp : IntIPv4) = {
        val echo = new ICMP()
        echo.setEchoRequest(16, 32, "My ICMP".getBytes)
        val eth: Ethernet = new Ethernet().
            setSourceMACAddress(srcMac).
            setDestinationMACAddress(dstMac).
            setEtherType(IPv4.ETHERTYPE)
        eth.setPayload(new IPv4().setSourceAddress(srcIp.addressAsInt).
            setDestinationAddress(dstIp.addressAsInt).
            setProtocol(ICMP.PROTOCOL_NUMBER).
            setPayload(echo))
        triggerPacketIn(portName, eth)
    }

    private def injectDhcpDiscover(portName: String, srcMac : MAC) {
        val dhcpDiscover = new DHCP()
        dhcpDiscover.setOpCode(0x01)
        dhcpDiscover.setHardwareType(0x01)
        dhcpDiscover.setHardwareAddressLength(6)
        dhcpDiscover.setClientHardwareAddress(srcMac)
        var options = mutable.ListBuffer[DHCPOption]()
        options.add(new DHCPOption(DHCPOption.Code.DHCP_TYPE.value,
                           DHCPOption.Code.DHCP_TYPE.length,
                           Array[Byte](DHCPOption.MsgType.DISCOVER.value)))
        dhcpDiscover.setOptions(options)
        val udp = new UDP()
        udp.setSourcePort((68).toShort)
        udp.setDestinationPort((67).toShort)
        udp.setPayload(dhcpDiscover)
        val eth = new Ethernet()
        eth.setSourceMACAddress(srcMac)
        eth.setDestinationMACAddress(MAC.fromString("ff:ff:ff:ff:ff:ff"))
        eth.setEtherType(IPv4.ETHERTYPE)
        eth.setPayload(new IPv4().setSourceAddress(0).
                                  setDestinationAddress(0xffffffff).
                                  setProtocol(UDP.PROTOCOL_NUMBER).
                                  setPayload(udp))
        triggerPacketIn(portName, eth)
    }

    private def injectDhcpRequest(portName : String, srcMac : MAC) {
        val dhcpReq = new DHCP()
        dhcpReq.setOpCode(0x01)
        dhcpReq.setHardwareType(0x01)
        dhcpReq.setHardwareAddressLength(6)
        dhcpReq.setClientHardwareAddress(srcMac)
        // server IP address should be set on DHCP request
        dhcpReq.setServerIPAddress(0)
        var options = mutable.ListBuffer[DHCPOption]()
        options.add(new DHCPOption(DHCPOption.Code.DHCP_TYPE.value,
                           DHCPOption.Code.DHCP_TYPE.length,
                           Array[Byte](DHCPOption.MsgType.REQUEST.value)))
        dhcpReq.setOptions(options)
        val udp = new UDP()
        udp.setSourcePort((68).toShort)
        udp.setDestinationPort((67).toShort)
        udp.setPayload(dhcpReq)
        val eth = new Ethernet()
        eth.setSourceMACAddress(srcMac)
        eth.setDestinationMACAddress(MAC.fromString("ff:ff:ff:ff:ff:ff"))
        eth.setEtherType(IPv4.ETHERTYPE)
        eth.setPayload(new IPv4().setSourceAddress(0).
                                  setDestinationAddress(0xffffffff).
                                  setProtocol(UDP.PROTOCOL_NUMBER).
                                  setPayload(udp))
        triggerPacketIn(portName, eth)
    }

    def test() {

        log.info("When the VM boots up, it should start sending DHCP discover")
        injectDhcpDiscover(vm2PortName, vm2Mac)
        requestOfType[PacketIn](simProbe())
        requestOfType[EmitGeneratedPacket](simProbe())
        log.info("Expecting MidoNet to respond with DHCP offer")
        // verify DHCP OFFER
        //expectPacketOnPort(brPort2.getId)

        log.info("Got DHCPOFFER, broadcast DHCP Request")
        injectDhcpRequest(vm2PortName, vm2Mac)
        requestOfType[PacketIn](simProbe())
        log.info("Expecting MidoNet to respond with DHCP Reply/Ack")
        // verify DHCP Reply
        //expectPacketOnPort(brPort2.getId)

        log.info("Sending ARP")
        //arpAndCheckReply(vm2PortName, vm2Mac, vm2IP, routerIp2,
        //                 routerMac2, vm2PortNumber)
        feedArpCache(vm2PortName, vm2IP.addressAsInt, vm2Mac, 
                     routerIp2.addressAsInt, routerMac2)
        requestOfType[PacketIn](simProbe())
        fishForRequestOfType[DiscardPacket](flowProbe())
        drainProbes()
        log.info("ARP Router port 1, and ping it too")
        arpAndCheckReply(vm2PortName, vm2Mac, vm2IP, routerIp1, routerMac2,
                         vm2PortNumber)
        requestOfType[PacketIn](simProbe())
        log.info("Ping Router port 2")
        injectIcmpEcho(vm2PortName, vm2Mac, vm2IP, routerMac2, routerIp2)
        requestOfType[PacketIn](simProbe())
        log.info("Check ICMP Echo Reply from Router port 2")
        expectEmitIcmp(routerMac2, routerIp2, vm2Mac, vm2IP,
                       ICMP.TYPE_ECHO_REPLY, ICMP.CODE_NONE)

    }
}
