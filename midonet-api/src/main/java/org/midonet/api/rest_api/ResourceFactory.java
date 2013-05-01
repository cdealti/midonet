/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.rest_api;

import org.midonet.api.filter.rest_api.RuleResource;
import org.midonet.api.host.rest_api.*;
import org.midonet.api.network.rest_api.*;
import org.midonet.api.bgp.rest_api.AdRouteResource;
import org.midonet.api.bgp.rest_api.BgpResource;
import org.midonet.api.dhcp.rest_api.BridgeDhcpResource;
import org.midonet.api.dhcp.rest_api.BridgeDhcpV6Resource;
import org.midonet.api.dhcp.rest_api.DhcpHostsResource;
import org.midonet.api.dhcp.rest_api.DhcpV6HostsResource;
import org.midonet.api.filter.rest_api.ChainResource;
import org.midonet.api.monitoring.rest_api.MonitoringResource;
import org.midonet.packets.IntIPv4;
import org.midonet.packets.IPv6Subnet;

import java.util.UUID;

/**
 * Resource factory used by Guice to inject resource classes.
 */
public interface ResourceFactory {

    RouterResource getRouterResource();

    BridgeResource getBridgeResource();

    PortResource getPortResource();

    RouteResource getRouteResource();

    ChainResource getChainResource();

    PortGroupResource getPortGroupResource();

    RuleResource getRuleResource();

    BgpResource getBgpResource();

    AdRouteResource getAdRouteResource();

    HostResource getHostResource();

    TunnelZoneResource getTunnelZoneResource();

    TunnelZoneHostResource getTunnelZoneHostResource(UUID id);

    HostInterfacePortResource getHostInterfacePortResource(UUID id);

    MonitoringResource getMonitoringQueryResource();

    AdRouteResource.BgpAdRouteResource getBgpAdRouteResource(UUID id);

    DhcpHostsResource getDhcpAssignmentsResource(UUID bridgeId, IntIPv4 addr);

    DhcpV6HostsResource getDhcpV6AssignmentsResource(UUID bridgeId, IPv6Subnet addr);

    PortResource.BridgePortResource getBridgePortResource(UUID id);

    BridgeDhcpResource getBridgeDhcpResource(UUID id);

    BridgeDhcpV6Resource getBridgeDhcpV6Resource(UUID id);

    PortResource.BridgePeerPortResource getBridgePeerPortResource(UUID id);

    RuleResource.ChainRuleResource getChainRuleResource(UUID id);

    InterfaceResource getInterfaceResource(UUID id);

    HostCommandResource getHostCommandsResource(UUID id);

    BgpResource.PortBgpResource getPortBgpResource(UUID id);

    PortResource.RouterPortResource getRouterPortResource(UUID id);

    RouteResource.RouterRouteResource getRouterRouteResource(UUID id);

    PortResource.RouterPeerPortResource getRouterPeerPortResource(UUID id);

    PortResource.PortGroupPortResource getPortGroupPortResource(UUID id);

}
