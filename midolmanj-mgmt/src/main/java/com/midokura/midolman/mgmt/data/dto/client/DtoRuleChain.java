package com.midokura.midolman.mgmt.data.dto.client;

/*
 * Copyright 2011 Midokura Europe SARL
 */

import java.net.URI;
import java.util.UUID;

import javax.xml.bind.annotation.XmlRootElement;

@XmlRootElement
public class DtoRuleChain {

    private UUID id;
    private UUID tenantId;
    private String name;
    private URI uri;
    private URI rules;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public void setTenantId(UUID tenantId) {
        this.tenantId = tenantId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public URI getUri() {
        return uri;
    }

    public void setUri(URI uri) {
        this.uri = uri;
    }

    public URI getRules() {
        return rules;
    }

    public void setRules(URI rules) {
        this.rules = rules;
    }

}
