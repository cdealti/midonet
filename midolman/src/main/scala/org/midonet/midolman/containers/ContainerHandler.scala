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

package org.midonet.midolman.containers

import scala.concurrent.Future

import com.google.common.base.MoreObjects

import rx.Observable

import org.midonet.cluster.models.State.ContainerStatus

case class ContainerHealth(code: ContainerStatus.Code, message: String) {
    override def toString = MoreObjects.toStringHelper(this).omitNullValues()
        .add("code", code)
        .toString
}

/**
  * A container handler provides a specific implementation for each container
  * type at the agent side. A container handler should, among other things,
  * create and delete the container namespace and VETH interface pair for a
  * specified exterior port. In addition, the container handler will ensure
  * that the appropriate services are started and configured for the given
  * service type and configuration, will update the container status, and may
  * provide container health-monitoring.
  */
trait ContainerHandler {

    /**
      * Creates a container for the specified exterior port and service
      * container. The port contains the interface name that the container
      * handler should create, and the method returns a future that completes
      * with the namespace name when the container has been created.
      *
      * Successful futures may contain:
      * - Some(name): when a namespace `name` was created on the host.
      * - None: when container was successfully handled but the namespace
      *   was not created for a legit. reason (e.g.: admin state DOWN.)
      *
      * Failed futures will contain any exception that prevented the handler
      * to spawn the service container.
      */
    def create(port: ContainerPort): Future[Option[String]]

    /**
      * Indicates that the configuration identifier for an existing container
      * has changed. This method is called only when the reference to the
      * configuration changes and not when the data of the existing configuration
      * objects change. It is the responsibility of the classes implementing
      * this interface to monitor their configuration.
      */
    def updated(port: ContainerPort): Future[Option[String]]

    /**
      * Deletes the container for the specified exterior port and namespace
      * information. The method returns a future that completes when the
      * container has been deleted.
      */
    def delete(): Future[Unit]

    /**
      * An observable that reports the health status of the container, which
      * includes both the container namespace/interface as well as the
      * service application executing within the container.
      */
    def health: Observable[ContainerHealth]

}
