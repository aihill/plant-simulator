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

package com.inland24.plantsim.services.simulator.rampUpType

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestKit}
import com.inland24.plantsim.models.DispatchCommand.DispatchRampUpPowerPlant
import com.inland24.plantsim.models.PowerPlantActorMessage._
import com.inland24.plantsim.models.PowerPlantConfig.RampUpTypeConfig
import com.inland24.plantsim.models.PowerPlantState.{Active, RampDown}
import com.inland24.plantsim.models.{
  PowerPlantActorMessage,
  PowerPlantType,
  ReturnToNormalCommand
}
import com.inland24.plantsim.models.PowerPlantType.RampUpType
import com.inland24.plantsim.services.simulator.rampUpType
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

import scala.concurrent.duration._

class RampUpTypeActorTest
    extends TestKit(ActorSystem("RampUpTypeActorTest"))
    with ImplicitSender
    with WordSpecLike
    with Matchers
    with BeforeAndAfterAll {

  override def afterAll: Unit = {
    TestKit.shutdownActorSystem(system)
  }

  private val rampUpTypeCfg = RampUpTypeConfig(
    id = 1,
    name = "someConfig",
    minPower = 400.0,
    maxPower = 800.0,
    rampPowerRate = 100.0,
    rampRateInSeconds = 2.seconds,
    powerPlantType = PowerPlantType.RampUpType
  )
  private val initPowerPlantState = StateMachine.init(rampUpTypeCfg)
  private val rampUpTypeActorCfg =
    rampUpType.RampUpTypeActor.Config(powerPlantCfg = rampUpTypeCfg)

  // Utility method to get the activePowerTolerance range
  def activePowerSignalRange(power: Double): Double =
    power * StateMachine.toleranceFactorInPercentage / 100

  "RampUpTypeActor" must {

    val rampUpTypeSimActor =
      system.actorOf(RampUpTypeActor.props(rampUpTypeActorCfg))

    // PowerPlant # Init / Active tests
    "start with minPower when initialized to Active state" in {
      // We do this shit just so that the Actor has some time to Init
      within(2.seconds) {
        expectNoMsg()
      }
      rampUpTypeSimActor ! PowerPlantActorMessage.StateRequestMessage
      expectMsgPF(2.seconds) {
        case state: StateMachine =>
          assert(state.signals === initPowerPlantState.signals,
                 "signals did not match")
          assert(state.cfg.id === initPowerPlantState.cfg.id,
                 "powerPlantId did not match")
          assert(
            state.cfg.rampPowerRate === initPowerPlantState.cfg.rampPowerRate,
            "rampRate did not match")
          assert(state.setPoint === initPowerPlantState.setPoint,
                 "setPoint did not match")
        case x: Any => // If I get any other message, I fail
          fail(
            s"Expected a PowerPlantState as message response from the Actor, but the response was $x")
      }

      // out activePower signal should be within the tolerance range
      val minPower = initPowerPlantState.cfg.minPower
      val beWithinTolerance =
        be >= (minPower - activePowerSignalRange(minPower)) and be <= (minPower + activePowerSignalRange(
          minPower))

      rampUpTypeSimActor ! TelemetrySignalsMessage
      expectMsgPF(2.seconds) {
        case signals: Map[_, _] =>
          signals.asInstanceOf[Map[String, String]] foreach {
            case (key1, value1) if key1 == StateMachine.isDispatchedSignalKey =>
              assert(!value1.toBoolean)
            case (key2, value2) if key2 == StateMachine.isAvailableSignalKey =>
              assert(value2.toBoolean)
            case (key3, value3) if key3 == StateMachine.activePowerSignalKey =>
              value3.toDouble should beWithinTolerance
            case (key4, value4) if key4 == StateMachine.powerPlantIdSignalKey =>
              assert(value4 === initPowerPlantState.cfg.id.toString)
            case (key0, _) =>
              assert(key0 === "setPoint" || key0 === "timestamp")
          }
        case x: Any => // If I get any other message, I fail
          fail(
            s"Expected a PowerPlantState as message response from the Actor, but the response was $x")
      }
    }

    // PowerPlant # RampUp tests
    "start to RampUp when a Dispatch command is sent" in {
      within(10.seconds) {
        rampUpTypeSimActor ! DispatchRampUpPowerPlant(
          powerPlantId = rampUpTypeCfg.id,
          command = "dispatch",
          powerPlantType = RampUpType,
          value = rampUpTypeCfg.maxPower
        )
        expectNoMsg
      }
      rampUpTypeSimActor ! StateRequestMessage
      expectMsgPF() {
        case state: StateMachine =>
          // check the signals
          assert(
            state.signals(StateMachine.activePowerSignalKey).toDouble === 800.0,
            "expecting activePower to be 800.0, but was not the case"
          )
          assert(
            state.signals(StateMachine.isDispatchedSignalKey).toBoolean,
            "expected isDispatched signal to be true, but was false instead"
          )
          assert(
            state.signals(StateMachine.isAvailableSignalKey).toBoolean,
            "expected isAvailable signal to be true, but was false instead"
          )
          assert(
            state.cfg.rampPowerRate === initPowerPlantState.cfg.rampPowerRate,
            "rampRate did not match"
          )
          assert(
            state.setPoint === 800.0,
            "setPoint did not match"
          )
        case x: Any => // If I get any other message, I fail
          fail(
            s"Expected a PowerPlantState as message response from the Actor, but the response was $x")
      }
    }

    "ignore Dispatch command when the dispatchPower is less than it's minPower" in {
      within(3.seconds) {
        rampUpTypeSimActor !
          DispatchRampUpPowerPlant(
            powerPlantId = rampUpTypeCfg.id,
            command = "dispatch",
            powerPlantType = RampUpType,
            value = rampUpTypeCfg.minPower - 1.0
          )
        expectNoMsg
      }
    }

    "dispatch to maxPower if the dispatchPower is more than the maxPower capacity of the PowerPlant" in {
      within(10.seconds) {
        // expected activePower should be this one here
        rampUpTypeSimActor !
          DispatchRampUpPowerPlant(
            powerPlantId = rampUpTypeCfg.id,
            command = "dispatch",
            powerPlantType = RampUpType,
            value = rampUpTypeCfg.maxPower + 1.0
          )
        expectNoMsg
      }
      rampUpTypeSimActor ! StateRequestMessage
      expectMsgPF() {
        case state: StateMachine =>
          // check the signals
          assert(
            state.signals(StateMachine.activePowerSignalKey).toDouble === 800.0,
            "expecting activePower to be 800.0, but was not the case"
          )
          assert(
            state.signals(StateMachine.isDispatchedSignalKey).toBoolean,
            "expected isDispatched signal to be true, but was false instead"
          )
          assert(
            state.signals(StateMachine.isAvailableSignalKey).toBoolean,
            "expected isAvailable signal to be true, but was false instead"
          )
          assert(
            state.cfg.rampPowerRate === initPowerPlantState.cfg.rampPowerRate,
            "rampRate did not match"
          )
          assert(
            state.setPoint === 800.0,
            "setPoint did not match"
          )
        case x: Any => // If I get any other message, I fail
          fail(
            s"Expected a PowerPlantState as message response from the Actor, but the response was $x")
      }
    }

    "ignore multiple Dispatch commands and should respond only to the first dispatch command" in {
      within(10.seconds) {
        // expected activePower should be this one here
        rampUpTypeSimActor !
          DispatchRampUpPowerPlant(
            powerPlantId = rampUpTypeCfg.id,
            command = "dispatch",
            powerPlantType = RampUpType,
            value = rampUpTypeCfg.maxPower
          )

        // this dispatch command should be ignored!!
        rampUpTypeSimActor !
          DispatchRampUpPowerPlant(
            powerPlantId = rampUpTypeCfg.id,
            command = "dispatch",
            powerPlantType = RampUpType,
            value = 10000.0
          )
        expectNoMsg
      }
      rampUpTypeSimActor ! StateRequestMessage
      expectMsgPF() {
        case state: StateMachine =>
          // check the signals
          assert(
            state.signals(StateMachine.activePowerSignalKey).toDouble === 800.0,
            "expecting activePower to be 800.0, but was not the case"
          )
          assert(
            state.signals(StateMachine.isDispatchedSignalKey).toBoolean,
            "expected isDispatched signal to be true, but was false instead"
          )
          assert(
            state.signals(StateMachine.isAvailableSignalKey).toBoolean,
            "expected isAvailable signal to be true, but was false instead"
          )
          assert(
            state.cfg.rampPowerRate === initPowerPlantState.cfg.rampPowerRate,
            "rampRate did not match"
          )
          assert(
            state.setPoint === 800.0,
            "setPoint did not match"
          )
        case x: Any => // If I get any other message, I fail
          fail(
            s"Expected a PowerPlantState as message response from the Actor, but the response was $x")
      }
    }

    // PowerPlant # OutOfService tests
    "send the PowerPlant into OutOfService when OutOfService message is sent during Active" in {
      within(5.seconds) {
        rampUpTypeSimActor ! OutOfServiceMessage
        expectNoMsg()
      }

      rampUpTypeSimActor ! StateRequestMessage
      expectMsgPF(5.seconds) {
        case state: StateMachine =>
          assert(
            state.signals === StateMachine.unAvailableSignals + (StateMachine.powerPlantIdSignalKey -> state.cfg.id.toString)
          )
        case x: Any =>
          fail(
            s"Expected a PowerPlantState as message response from the Actor, but the response was $x")
      }
    }

    "throw the PowerPlant into OutOfService when OutOfService message is sent during RampUp" in {
      // 1. Send a Dispatch message
      within(2.seconds) {
        rampUpTypeSimActor ! DispatchRampUpPowerPlant(
          powerPlantId = rampUpTypeCfg.id,
          command = "dispatch",
          powerPlantType = RampUpType,
          value = rampUpTypeCfg.maxPower
        )
        expectNoMsg()
      }

      // 2. Send a OutOfService message
      rampUpTypeSimActor ! OutOfServiceMessage

      // 3. Send a StateRequest message
      rampUpTypeSimActor ! StateRequestMessage
      expectMsgPF() {
        case state: StateMachine =>
          assert(
            state.signals === StateMachine.unAvailableSignals + (StateMachine.powerPlantIdSignalKey -> state.cfg.id.toString))
        case x: Any =>
          fail(
            s"Expected a PowerPlantState as message response from the Actor, but the response was $x")
      }
    }

    // PowerPlant # ReturnToService tests
    "return the PowerPlant from OutOfService to Active when sending ReturnToServiceMessage message" in {
      // 1. First make the PowerPlant OutOfService
      within(3.seconds) {
        rampUpTypeSimActor ! OutOfServiceMessage
        expectNoMsg()
      }

      // 2. Send a ReturnToService message
      within(3.seconds) {
        rampUpTypeSimActor ! ReturnToServiceMessage
      }

      // 3. Send a StateRequest message and check the signals
      within(10.seconds) {
        rampUpTypeSimActor ! StateRequestMessage
        expectMsgPF() {
          case state: StateMachine =>
            assert(state.signals === initPowerPlantState.signals,
                   "signals did not match")
            assert(state.cfg.id === initPowerPlantState.cfg.id,
                   "powerPlantId did not match")
            assert(
              state.cfg.rampPowerRate === initPowerPlantState.cfg.rampPowerRate,
              "rampRate did not match")
            assert(state.setPoint === initPowerPlantState.cfg.minPower,
                   "setPoint did not match")
          case x: Any =>
            fail(
              s"Expected a PowerPlantState as message response from the Actor, but the response was $x")
        }
      }
    }

    // PowerPlant # ReturnToNormal tests
    // TODO: Re-work on this test to perfection! Currently it blocks thread! Try using receiveWhile from the TestKit
    "return the PowerPlant to Normal when ReturnToNormalCommand message is sent in dispatched state" in {
      // To avoid confusion and the tests failing, we create a new actor instance for this test
      val rampUpTypeActor =
        system.actorOf(RampUpTypeActor.props(rampUpTypeActorCfg))

      // 1. Send a Dispatch message
      within(10.seconds) {
        rampUpTypeActor ! DispatchRampUpPowerPlant(
          powerPlantId = rampUpTypeCfg.id,
          command = "dispatch",
          powerPlantType = RampUpType,
          value = rampUpTypeCfg.maxPower
        )
        expectNoMsg()
      }

      /*
       * Very unfortunately, we got to bloddy wait for some time until our Actor changes context!
       * This happens only for unit testing as there is no Akka Testkit support for this behavior
       */
      Thread.sleep(10000) // We sleep for 8 seconds, to give some time for our Actor to change context!!!

      within(2.seconds) {
        rampUpTypeActor ! ReturnToNormalCommand(rampUpTypeCfg.id)
        expectNoMsg()
      }

      /*
       * Very unfortunately, we got to bloddy wait for some time until our Actor changes context!
       * This happens only for unit testing as there is no Akka TestKit support for this behavior
       */
      Thread.sleep(8000) // We sleep for 8 seconds, give some time for our Actor to change context!!!

      // 3. The PowerPlant should have fully returned to normal, let's check that
      within(4.seconds) {
        rampUpTypeActor ! StateRequestMessage
        expectMsgPF() {
          case state: StateMachine =>
            state.newState shouldBe Active
            state.oldState shouldBe RampDown
            state.setPoint shouldBe initPowerPlantState.cfg.maxPower
            // PowerPlant should be dispatched false as it comes back to active state
            state
              .signals(StateMachine.isDispatchedSignalKey)
              .toBoolean shouldBe false
            state
              .signals(StateMachine.isAvailableSignalKey)
              .toBoolean shouldBe true
            state
              .signals(StateMachine.activePowerSignalKey)
              .toDouble shouldBe initPowerPlantState.cfg.minPower

          case x: Any =>
            fail(
              s"Expected a PowerPlantState as message response from the Actor, but the response was $x")
        }
      }
    }
  }
}
