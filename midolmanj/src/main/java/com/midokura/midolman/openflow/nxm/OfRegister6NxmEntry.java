/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.openflow.nxm;

/**
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 4/3/12
 */
public class OfRegister6NxmEntry extends IntNomaskNxmEntry {

    public OfRegister6NxmEntry(int value) {
        super(value);
    }

    public OfRegister6NxmEntry() {
    }

    @Override
    public NxmType getNxmType() {
        return NxmType.NXM_REGISTER_6;
    }
}
