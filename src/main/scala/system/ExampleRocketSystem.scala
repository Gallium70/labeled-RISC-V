// See LICENSE.SiFive for license details.

package freechips.rocketchip.system

import Chisel._
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.subsystem._
import freechips.rocketchip.devices.tilelink._
import freechips.rocketchip.util.DontTouch
import boom.system._

/** Example Top with periphery devices and ports, and a Rocket subsystem */
class ExampleBoomSystem(implicit p: Parameters) extends BoomSubsystem
    with HasAsyncExtInterrupts
//    with CanHaveMasterAXI4MemPort
    with CanHaveMisalignedMasterAXI4MemPort
    with CanHaveMasterAXI4MMIOPort
    with CanHaveSlaveAXI4Port
    with HasPeripheryBootROM {
  override lazy val module = new ExampleBoomSystemModuleImp(this)

  // The sbus masters the cbus; here we convert TL-UH -> TL-UL
  sbus.crossToBus(cbus, NoCrossing)

  // The cbus masters the pbus; which might be clocked slower
  cbus.crossToBus(pbus, SynchronousCrossing())

  // The fbus masters the sbus; both are TL-UH or TL-C
  FlipRendering { implicit p =>
    sbus.crossFromBus(fbus, SynchronousCrossing())
  }

  // The sbus masters the mbus; here we convert TL-C -> TL-UH
  private val BankedL2Params(nBanks, coherenceManager) = p(BankedL2Key)
  private val (in, out, halt) = coherenceManager(this)
  if (nBanks != 0) {
    sbus.coupleTo("coherence_manager") { in :*= _ }
    mbus.coupleFrom("coherence_manager") { _ :=* BankBinder(mbus.blockBytes * (nBanks-1)) :*= out }
  }
}

class ExampleBoomSystemModuleImp[+L <: ExampleBoomSystem](_outer: L) extends BoomSubsystemModule(_outer)
    with HasRTCModuleImp
    with HasExtInterruptsModuleImp
//    with CanHaveMasterAXI4MemPortModuleImp
    with CanHaveMisalignedMasterAXI4MemPortModuleImp
    with CanHaveMasterAXI4MMIOPortModuleImp
    with CanHaveSlaveAXI4PortModuleImp
    with HasPeripheryBootROMModuleImp
    with DontTouch


/** Example Top with periphery devices and ports, and a Rocket subsystem */
class ExampleRocketSystem(implicit p: Parameters) extends RocketSubsystem
    with HasAsyncExtInterrupts
    with HasChiplinkPort
    with CanHaveSlaveAXI4Port
    with HasPeripheryBootROM {
  override lazy val module = new ExampleRocketSystemModuleImp(this)

  // The sbus masters the cbus; here we convert TL-UH -> TL-UL
  sbus.crossToBus(cbus, NoCrossing)

  // The cbus masters the pbus; which might be clocked slower
  cbus.crossToBus(pbus, SynchronousCrossing())

  // The fbus masters the sbus; both are TL-UH or TL-C
  FlipRendering { implicit p =>
    sbus.crossFromBus(fbus, SynchronousCrossing())
  }

  // The sbus masters the mbus; here we convert TL-C -> TL-UH
  private val BankedL2Params(nBanks, coherenceManager) = p(BankedL2Key)
  private val (in, out, halt) = coherenceManager(this)
  if (nBanks != 0) {
    sbus.coupleTo("coherence_manager") { in :*= _ }
    mbus.coupleFrom("coherence_manager") { _ :=* BankBinder(mbus.blockBytes * (nBanks-1)) :*= out }
  }
}

class ExampleRocketSystemModuleImp[+L <: ExampleRocketSystem](_outer: L) extends RocketSubsystemModuleImp(_outer)
    with HasRTCModuleImp
    with HasExtInterruptsModuleImp
    with CanHaveSlaveAXI4PortModuleImp
    with HasChiplinkPortImpl
    with HasPeripheryBootROMModuleImp
    with DontTouch

///** Example Top with periphery devices and ports, and a Rocket subsystem */
//class ExampleRocketSystemAHB(implicit p: Parameters) extends RocketSubsystem
//  with HasAsyncExtInterrupts
//  with CanHaveMasterAHBMemPort
//  with CanHaveMasterAHBMMIOPort
//  with CanHaveSlaveAXI4Port
//  with HasPeripheryBootROM {
//  override lazy val module = new ExampleRocketSystemModuleAHBImp(this)
//}
//
//class ExampleRocketSystemModuleAHBImp[+L <: ExampleRocketSystemAHB](_outer: L) extends RocketSubsystemModuleImp(_outer)
//  with HasRTCModuleImp
//  with HasExtInterruptsModuleImp
//  with CanHaveMasterAHBMemPortModuleImp
//  with CanHaveMasterAHBMMIOPortModuleImp
//  with CanHaveSlaveAXI4PortModuleImp
//  with HasPeripheryBootROMModuleImp
//  with DontTouch
