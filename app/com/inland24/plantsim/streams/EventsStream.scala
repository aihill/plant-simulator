/*
 * Copyright (c) 2017 joesan @ http://github.com/joesan
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.inland24.plantsim.streams

import akka.actor.{Actor, ActorLogging, Props}
import com.inland24.plantsim.models.PowerPlantSignal
import com.inland24.plantsim.models.PowerPlantSignal.{DispatchAlert, Genesis, Transition}
import monix.reactive.subjects.ConcurrentSubject


final class EventsStream(channel: ConcurrentSubject[PowerPlantSignal, PowerPlantSignal])
  extends Actor with ActorLogging {

  override def preStart = {
    super.preStart()
  }

  override def receive = {
    case t: Transition =>
      channel.onNext(t)
    case g: Genesis =>
      channel.onNext(g)
    case d: DispatchAlert =>
      channel.onNext(d)
    case _ =>
      log.info(s"**** Doing Nothing ****")
  }
}
object EventsStream {

  def props(publishChannel: ConcurrentSubject[PowerPlantSignal, PowerPlantSignal]) =
    Props(new EventsStream(publishChannel))
}