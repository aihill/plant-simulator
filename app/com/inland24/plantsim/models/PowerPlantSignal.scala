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
package com.inland24.plantsim.models

import org.joda.time.DateTime


// TODO: Scaladoc!!!!
sealed trait PowerPlantSignal {
  def powerPlantConfig: PowerPlantConfig
  def timeStamp: DateTime
}
object PowerPlantSignal {

  // **** Events about State transitions that happen in a PowerPlant
  sealed trait StateTransitionEvent extends PowerPlantSignal {
    def newState: PowerPlantRunState
  }

  case class Genesis(
    newState: PowerPlantRunState,
    timeStamp: DateTime,
    powerPlantConfig: PowerPlantConfig,
  ) extends StateTransitionEvent

  case class Transition(
    oldState: PowerPlantRunState,
    newState: PowerPlantRunState,
    timeStamp: DateTime,
    powerPlantConfig: PowerPlantConfig
  ) extends StateTransitionEvent
  // **** *****

  // **** Alerts that happen in the system when interacting with the PowerPlant
  sealed trait PowerPlantAlert extends PowerPlantSignal

  case class DispatchAlert(
    msg: String,
    timeStamp: DateTime,
    powerPlantConfig: PowerPlantConfig
  ) extends PowerPlantAlert
}