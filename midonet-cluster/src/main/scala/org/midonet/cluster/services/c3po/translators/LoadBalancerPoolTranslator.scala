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

import org.midonet.cluster.data.storage.{NotFoundException, ReadOnlyStorage}
import org.midonet.cluster.models.Commons.UUID
import org.midonet.cluster.models.Neutron.NeutronLoadBalancerPool
import org.midonet.cluster.models.Topology.{LoadBalancer, Pool}
import org.midonet.cluster.services.c3po.C3POStorageManager.{Create, Delete, Update}
import org.midonet.util.concurrent.toFutureOps

/** Provides a Neutron model translator for NeutronLoadBalancerPool. */
class LoadBalancerPoolTranslator(protected val storage: ReadOnlyStorage)
        extends Translator[NeutronLoadBalancerPool]
                with LoadBalancerManager {

    override protected def translateCreate(nPool: NeutronLoadBalancerPool)
    : OperationList = {
        if (!nPool.hasRouterId)
            throw new IllegalArgumentException("No router ID is specified.")
        if (nPool.getHealthMonitorsCount > 0)
            throw new IllegalArgumentException(
                "A health monitor may be associated with a pool only at the " +
                "time of the health monitor's creation.")

        // If no Load Balancer has been created for the Router, create one.
        val lbId = loadBalancerId(nPool.getRouterId)
        val midoOps = new OperationListBuffer
        if (!storage.exists(classOf[LoadBalancer], lbId).await()) {
            val lb = LoadBalancer.newBuilder()
                                 .setId(lbId)
                                 .setAdminStateUp(nPool.getAdminStateUp)
                                 .setRouterId(nPool.getRouterId).build()
            midoOps += Create(lb)
        }

        // Create a MidoNet Pool.
        midoOps += Create(translatePool(nPool))

        midoOps.toList
    }

    override protected def translateDelete(id: UUID): OperationList = {
        val pool = try storage.get(classOf[Pool], id).await() catch {
            case e: NotFoundException =>
                return List.empty // idempotent deletes
        }

        val delPool = Delete(classOf[Pool], id)
        val lbId = pool.getLoadBalancerId // if !hasLoadBalancerId it's a bug
        val lb = storage.get(classOf[LoadBalancer], lbId).await()
        val maybeDelLB = if (lb.getPoolIdsCount == 1) {
            List(Delete(classOf[LoadBalancer], lbId))
        } else {
            List.empty
        }
        delPool +: maybeDelLB
    }

    override protected def translateUpdate(nPool: NeutronLoadBalancerPool)
    : OperationList = {
        val oldPool = storage.get(classOf[Pool], nPool.getId).await()
        val updatedPool = oldPool.toBuilder
            .setAdminStateUp(nPool.getAdminStateUp)
            .build()
        List(Update(updatedPool))
    }

    private def translatePool(nPool: NeutronLoadBalancerPool): Pool = {
        Pool.newBuilder
            .setId(nPool.getId)
            .setLoadBalancerId(loadBalancerId(nPool.getRouterId))
            .setAdminStateUp(nPool.getAdminStateUp)
            .build()
    }
}
