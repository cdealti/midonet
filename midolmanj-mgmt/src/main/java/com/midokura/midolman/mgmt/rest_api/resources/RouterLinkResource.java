/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.rest_api.resources;

import java.util.List;
import java.util.UUID;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import com.midokura.midolman.mgmt.auth.AuthAction;
import com.midokura.midolman.mgmt.auth.Authorizer;
import com.midokura.midolman.mgmt.data.DaoFactory;
import com.midokura.midolman.mgmt.data.dao.RouterLinkDao;
import com.midokura.midolman.mgmt.data.dto.LogicalRouterPort;
import com.midokura.midolman.mgmt.data.dto.PeerRouterLink;
import com.midokura.midolman.mgmt.data.dto.UriResource;
import com.midokura.midolman.mgmt.rest_api.core.VendorMediaType;
import com.midokura.midolman.mgmt.rest_api.jaxrs.ForbiddenHttpException;
import com.midokura.midolman.mgmt.rest_api.jaxrs.NotFoundHttpException;
import com.midokura.midolman.state.StateAccessException;

/**
 * Sub-resource class for router's peer router.
 */
public class RouterLinkResource {

    private UUID routerId = null;

    /**
     * Constructor
     *
     * @param routerId
     *            ID of a router.
     */
    public RouterLinkResource(UUID routerId) {
        this.routerId = routerId;
    }

    /**
     * Handler for creating a router to router link.
     *
     * @param port
     *            LogicalRouterPort object.
     * @param uriInfo
     *            Object that holds the request URI data.
     * @param context
     *            Object that holds the security data.
     * @param daoFactory
     *            Data access factory object.
     * @param authorizer
     *            Authorizer object.
     * @throws StateAccessException
     *             Data access error.
     * @returns Response object with 201 status code set if successful. Body is
     *          set to PeerRouterLink.
     */
    @POST
    @Consumes({ VendorMediaType.APPLICATION_PORT_JSON,
            MediaType.APPLICATION_JSON })
    @Produces({ VendorMediaType.APPLICATION_ROUTER_LINK_JSON,
            MediaType.APPLICATION_JSON })
    public Response create(LogicalRouterPort port, @Context UriInfo uriInfo,
            @Context SecurityContext context, @Context DaoFactory daoFactory,
            @Context Authorizer authorizer) throws StateAccessException {

        if (!authorizer.routerLinkAuthorized(context, AuthAction.WRITE,
                port.getDeviceId(), port.getPeerRouterId())) {
            throw new ForbiddenHttpException(
                    "Not authorized to link these routers.");
        }

        RouterLinkDao dao = daoFactory.getRouterLinkDao();
        port.setDeviceId(routerId);
        PeerRouterLink peerRouter = dao.create(port);
        if (peerRouter != null) {
            peerRouter.setBaseUri(uriInfo.getBaseUri());
        }
        return Response.created(peerRouter.getUri()).entity(peerRouter).build();
    }

    /**
     * Handler to deleting a router link.
     *
     * @param peerId
     *            Peer router ID from the request.
     * @param context
     *            Object that holds the security data.
     * @param daoFactory
     *            Data access factory object.
     * @param authorizer
     *            Authorizer object.
     * @throws StateAccessException
     *             Data access error.
     */
    @DELETE
    @Path("{id}")
    public void delete(@PathParam("id") UUID peerId,
            @Context SecurityContext context, @Context DaoFactory daoFactory,
            @Context Authorizer authorizer) throws StateAccessException {

        if (!authorizer.routerLinkAuthorized(context, AuthAction.WRITE,
                routerId, peerId)) {
            throw new ForbiddenHttpException(
                    "Not authorized to unlink these routers.");
        }

        RouterLinkDao dao = daoFactory.getRouterLinkDao();
        dao.delete(routerId, peerId);
    }

    /**
     * Handler to getting a router to router link.
     *
     * @param id
     *            Peer router ID from the request.
     * @param context
     *            Object that holds the security data.
     * @param uriInfo
     *            Object that holds the request URI data.
     * @param daoFactory
     *            Data access factory object.
     * @param authorizer
     *            Authorizer object.
     * @throws StateAccessException
     *             Data access error.
     * @return A PeerRouterLink object.
     */
    @GET
    @Path("{id}")
    @Produces({ VendorMediaType.APPLICATION_ROUTER_LINK_JSON,
            MediaType.APPLICATION_JSON })
    public PeerRouterLink get(@PathParam("id") UUID id,
            @Context SecurityContext context, @Context UriInfo uriInfo,
            @Context DaoFactory daoFactory, @Context Authorizer authorizer)
            throws StateAccessException {

        if (!authorizer.routerLinkAuthorized(context, AuthAction.READ,
                routerId, id)) {
            throw new ForbiddenHttpException(
                    "Not authorized to view this router link.");
        }

        RouterLinkDao dao = daoFactory.getRouterLinkDao();
        PeerRouterLink link = dao.get(routerId, id);
        if (link == null) {
            throw new NotFoundHttpException(
                    "The requested resource was not found.");
        }
        link.setBaseUri(uriInfo.getBaseUri());

        return link;
    }

    /**
     * Handler to list router links.
     *
     * @param context
     *            Object that holds the security data.
     * @param uriInfo
     *            Object that holds the request URI data.
     * @param daoFactory
     *            Data access factory object.
     * @param authorizer
     *            Authorizer object.
     * @throws StateAccessException
     *             Data access error.
     * @return A list of PeerRouterLink objects.
     */
    @GET
    @Produces({ VendorMediaType.APPLICATION_ROUTER_LINK_COLLECTION_JSON,
            MediaType.APPLICATION_JSON })
    public List<PeerRouterLink> list(@Context SecurityContext context,
            @Context UriInfo uriInfo, @Context DaoFactory daoFactory,
            @Context Authorizer authorizer) throws StateAccessException {

        if (!authorizer.routerAuthorized(context, AuthAction.READ, routerId)) {
            throw new ForbiddenHttpException(
                    "Not authorized to view these router links.");
        }

        RouterLinkDao dao = daoFactory.getRouterLinkDao();
        List<PeerRouterLink> links = dao.list(routerId);
        if (links != null) {
            for (UriResource resource : links) {
                resource.setBaseUri(uriInfo.getBaseUri());
            }
        }
        return links;
    }

}