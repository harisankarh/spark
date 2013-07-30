/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package spark.scheduler.cluster

import scala.collection.mutable.{ArrayBuffer, HashMap, HashSet}

import akka.actor._
import akka.util.duration._
import akka.pattern.ask
import akka.util.Duration

import spark.{Utils, SparkException, Logging, TaskState}
import akka.dispatch.Await
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.LinkedBlockingQueue
import akka.remote.{RemoteClientShutdown, RemoteClientDisconnected, RemoteClientLifeCycleEvent}

/**
 * A standalone scheduler backend, which waits for standalone executors to connect to it through
 * Akka. These may be executed in a variety of ways, such as Mesos tasks for the coarse-grained
 * Mesos mode or standalone processes for Spark's standalone deploy mode (spark.deploy.*).
 */
private[spark]
class StandaloneSchedulerBackend(scheduler: ClusterScheduler, actorSystem: ActorSystem)
  extends SchedulerBackend with Logging {
  // All uses of executorActor must be synchronized, because task launch occurs in a separate
  // thread and uses executorActor.
  private val executorActor = new HashMap[String, ActorRef]

  // Use an atomic variable to track total number of cores in the cluster for simplicity and speed
  var totalCoreCount = new AtomicInteger(0)

  class DriverActor(sparkProperties: Seq[(String, String)]) extends Actor {
    private val executorAddress = new HashMap[String, Address]
    private val executorHostPort = new HashMap[String, String]
    private val freeCores = new HashMap[String, Int]
    private val actorToExecutorId = new HashMap[ActorRef, String]
    private val addressToExecutorId = new HashMap[Address, String]

    override def preStart() {
      // Listen for remote client disconnection events, since they don't go through Akka's watch()
      context.system.eventStream.subscribe(self, classOf[RemoteClientLifeCycleEvent])
    }

    def receive = {
      case RegisterExecutor(executorId, hostPort, cores) =>
        Utils.checkHostPort(hostPort, "Host port expected " + hostPort)
        executorActor.synchronized {
          if (executorActor.contains(executorId)) {
            sender ! RegisterExecutorFailed("Duplicate executor ID: " + executorId)
          } else {
            logInfo("Registered executor: " + sender + " with ID " + executorId)
            sender ! RegisteredExecutor(sparkProperties)
            context.watch(sender)
            executorActor(executorId) = sender
            executorHostPort(executorId) = hostPort
            freeCores(executorId) = cores
            executorAddress(executorId) = sender.path.address
            actorToExecutorId(sender) = executorId
            addressToExecutorId(sender.path.address) = executorId
            totalCoreCount.addAndGet(cores)
            makeOffers()
          }
        }

      case StatusUpdate(executorId, taskId, state, data) =>
        scheduler.statusUpdate(taskId, state, data.value)
        if (TaskState.isFinished(state)) {
          freeCores(executorId) += 1
          makeOffers(executorId)
        }

      case ReviveOffers =>
        makeOffers()

      case StopDriver =>
        sender ! true
        context.stop(self)

      case RemoveExecutor(executorId, reason) =>
        removeExecutor(executorId, reason)
        sender ! true

      case Terminated(actor) =>
        actorToExecutorId.get(actor).foreach(removeExecutor(_, "Akka actor terminated"))

      case RemoteClientDisconnected(transport, address) =>
        addressToExecutorId.get(address).foreach(removeExecutor(_, "remote Akka client disconnected"))

      case RemoteClientShutdown(transport, address) =>
        addressToExecutorId.get(address).foreach(removeExecutor(_, "remote Akka client shutdown"))

      case FreeCores(executorIdsToCores) =>
        executorIdsToCores.foreach{ case(id, numCores) => freeCores(id) += numCores }
    }

    def makeOffers(executorId: String) {
      scheduler.addResourceOffer(
        new WorkerOffer(executorId, executorHostPort(executorId), freeCores(executorId)))
      freeCores(executorId) = 0
    }

    def makeOffers() {
      scheduler.addResourceOffers(executorHostPort.toArray.map {
        case (id, hostPort) =>
          // Mark the cores as "in use" while resource offers are being made to avoid offering the same cores to 2 jobs.
          val numFreeCores = freeCores(id)
          freeCores(id) = 0
          new WorkerOffer(id, hostPort, numFreeCores)
      })
    }
/*
    def makeOffers() {
      val resourceOffers = executorHostPort.toArray.flatMap {
        case (id, hostPort) => 
          if (freeCores(id) > 0) Some(new WorkerOffer(id, hostPort, freeCores(id)))
          else None }
      // TODO: do this with a foreach to have only one method, and see if it impacts throughout.
      if (resourceOffers.length > 0) {
        scheduler.addResourceOffers(resourceOffers)
      }
    }
*/

    // Remove a disconnected slave from the cluster
    def removeExecutor(executorId: String, reason: String) {
      executorActor.synchronized {
        if (executorActor.contains(executorId)) {
          logInfo("Executor " + executorId + " disconnected, so removing it")
          val numCores = freeCores(executorId)
          actorToExecutorId -= executorActor(executorId)
          addressToExecutorId -= executorAddress(executorId)
          executorActor -= executorId
          executorHostPort -= executorId
          freeCores -= executorId
          executorHostPort -= executorId
          totalCoreCount.addAndGet(-numCores)
          scheduler.executorLost(executorId, SlaveLost(reason))
        }
      }
    }
  }

  var driverActor: ActorRef = null
  val taskIdsOnSlave = new HashMap[String, HashSet[String]]

  val tasksToLaunch = new LinkedBlockingQueue[TaskDescription]

  def launchTask(task: TaskDescription) {
    tasksToLaunch.put(task)
  }

  override def start() {
    val properties = new ArrayBuffer[(String, String)]
    val iterator = System.getProperties.entrySet.iterator
    while (iterator.hasNext) {
      val entry = iterator.next
      val (key, value) = (entry.getKey.toString, entry.getValue.toString)
      if (key.startsWith("spark.") && !key.equals("spark.hostPort")) {
        properties += ((key, value))
      }
    }
    driverActor = actorSystem.actorOf(
      Props(new DriverActor(properties)), name = StandaloneSchedulerBackend.ACTOR_NAME)

    // Start thread to launch tasks.
    new Thread("Task Launching") {
      setDaemon(true)
 
      override def run() {
        var task : TaskDescription = null
        while (true) {
          try {
            task = tasksToLaunch.take()
            executorActor.synchronized {
              // TODO: handle failures
              executorActor(task.executorId) ! LaunchTask(task)
            }
          } catch {
            case interruptedException: InterruptedException =>
              logError(
                "Interruped while waiting for task to launch: %s".format(interruptedException))
            case e: Exception =>
              logError(e.getMessage())
          }
        } 
      }
    }.start() 
  }

  private val timeout = Duration.create(System.getProperty("spark.akka.askTimeout", "10").toLong, "seconds")

  override def stop() {
    try {
      if (driverActor != null) {
        val future = driverActor.ask(StopDriver)(timeout)
        Await.result(future, timeout)
      }
    } catch {
      case e: Exception =>
        throw new SparkException("Error stopping standalone scheduler's driver actor", e)
    }
  }

  override def reviveOffers() {
    driverActor ! ReviveOffers
  }

  override def defaultParallelism() = Option(System.getProperty("spark.default.parallelism"))
      .map(_.toInt).getOrElse(math.max(totalCoreCount.get(), 2))

  // Called by subclasses when notified of a lost worker
  def removeExecutor(executorId: String, reason: String) {
    try {
      val future = driverActor.ask(RemoveExecutor(executorId, reason))(timeout)
      Await.result(future, timeout)
    } catch {
      case e: Exception =>
        throw new SparkException("Error notifying standalone scheduler's driver actor", e)
    }
  }
}

private[spark] object StandaloneSchedulerBackend {
  val ACTOR_NAME = "StandaloneScheduler"
}
