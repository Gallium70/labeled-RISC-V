// See LICENSE.SiFive for license details.

package freechips.rocketchip.system

import Chisel._
import chisel3.core.DontCare
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.devices.debug.Debug
import freechips.rocketchip.diplomacy.LazyModule
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.subsystem._
import freechips.rocketchip.system._

class SimFront()(implicit p: Parameters) extends Module {
  val io = new Bundle {
  }
}
class BoomTestHarness()(implicit p: Parameters) extends Module {
  val io = new Bundle {
    val success = Bool(OUTPUT)
  }

  // Weird compilation failure...
  // val dut = Module(LazyModule(if (p(UseEmu)) new LvNAEmuTop else new LvNAFPGATop).module)
  val dut = if (p(UseEmu)) Module(LazyModule(new LvNABoomEmuTop).module) else Module(LazyModule(new LvNABoomFPGATop).module)
  dut.reset := reset.toBool | dut.debug.ndreset

  dut.dontTouchPorts()
  dut.tieOffInterrupts()
  dut.connectSimAXIMem()
  dut.connectSimAXIMMIO()
  dut.l2_frontend_bus_axi4.foreach( q => q := DontCare ) // Overridden in next line

  Debug.connectDebug(dut.debug, clock, reset.toBool, io.success)

  val enableFrontBusTraffic = false
  val frontBusAccessAddr = 0x10000fc00L

  if (!enableFrontBusTraffic) {
    dut.l2_frontend_bus_axi4.foreach(_.tieoff)
  }
  else {
    val axi = dut.l2_frontend_bus_axi4.head
    axi.r.ready := Bool(true)
    axi.b.ready := Bool(true)
    axi.ar.valid := Bool(false)

    val IDLE = 0
    val WADDR = 1
    val state = RegInit(IDLE.U)
    val awvalid = RegInit(false.B)
    val wvalid = RegInit(false.B)
    val wlast = RegInit(false.B)
    val next_state = Wire(state.cloneType)
    state := next_state
    val (value, timeout) = Counter(state === IDLE.U, 300)
    when(state === IDLE.U) {
      next_state := Mux(timeout, WADDR.U, IDLE.U)
    }.elsewhen(state === WADDR.U) {
      awvalid := true.B
      wvalid := true.B
      wlast := true.B
      when(axi.aw.fire()) {
        awvalid := false.B
        wvalid := false.B
        wlast := false.B
        next_state := IDLE.U
      }.otherwise {
        next_state := WADDR.U
      }
    }.otherwise {
      printf("Unexpected frontend axi state: %d", state)
    }

    axi.w.valid := wvalid
    axi.aw.valid := awvalid
    axi.w.bits.last := wlast
    axi.aw.bits.id := 111.U
    axi.aw.bits.addr := frontBusAccessAddr.U
    axi.aw.bits.len := 0.U // Curr cycle data?
    axi.aw.bits.size := 2.U // 2^2 = 4 bytes
    axi.aw.bits.burst := AXI4Parameters.BURST_INCR
    axi.aw.bits.lock := UInt(0)
    axi.aw.bits.cache := UInt(0)
    axi.aw.bits.prot := AXI4Parameters.PROT_PRIVILEDGED
    axi.aw.bits.qos := UInt(0)
    axi.w.bits.data := UInt(0xdeadbeefL)
    axi.w.bits.strb := UInt(0xf) // only lower 4 bits is allowed to be 1.
  }
}

class TestHarness()(implicit p: Parameters) extends Module {
  val io = new Bundle {
    val success = Bool(OUTPUT)
  }

  val dualTop = Module(LazyModule(new DualTop).module)

  dualTop.corerst := dualTop.reset
  dualTop.coreclk := dualTop.clock

  dualTop.dontTouchPorts()
  Debug.connectDebug(dualTop.debug, clock, reset, io.success)
}


class TestHarness2()(implicit p: Parameters) extends Module {
  val io = new Bundle {
    val success = Bool(OUTPUT)
  }

  val chip = Module(LazyModule(new ChiplinkTop).module)
  val dut = if (p(UseEmu)) Module(LazyModule(new LvNAEmuTop).module) else Module(LazyModule(new LvNAFPGATop).module)

  dut.reset := reset.toBool() | dut.debug.ndreset
  chip.reset := reset.toBool() | dut.debug.ndreset

  dut.corerst := dut.reset
  dut.coreclk := dut.clock

  dut.io_chip.b2c <> chip.fpga_io.c2b
  chip.fpga_io.b2c <> dut.io_chip.c2b

  dut.dontTouchPorts()
  dut.tieOffInterrupts()
  chip.connectSimAXIMem()
  chip.connectSimAXIMMIO()

  chip.l2_frontend_bus_axi4.foreach( q => q := DontCare ) // Overridden in next line

  dut.l2_frontend_bus_axi4 <> dut.axi4_mem.get

  Debug.connectDebug(dut.debug, clock, reset, io.success)

  val enableFrontBusTraffic = false
  val frontBusAccessAddr = 0x10000fc00L

  if (!enableFrontBusTraffic) {
    chip.l2_frontend_bus_axi4.foreach(_.tieoff)
  }
  else {
    val axi = chip.l2_frontend_bus_axi4.head
    axi.r.ready := Bool(true)
    axi.b.ready := Bool(true)
    axi.ar.valid := Bool(false)

    val IDLE = 0
    val WADDR = 1
    val STOP = 2

    val state = RegInit(IDLE.U)
    val awvalid = RegInit(false.B)
    val wvalid = RegInit(false.B)
    val wlast = RegInit(false.B)
    val (value, timeout) = Counter(state === IDLE.U, 300)
    when(state === IDLE.U) {
      state := Mux(timeout, WADDR.U, IDLE.U)
    }.elsewhen(state === WADDR.U) {
      awvalid := true.B
      wvalid := true.B
      wlast := true.B
      printf("Hello\n")
      when(axi.aw.fire()) {
        awvalid := false.B
        wvalid := false.B
        wlast := false.B
        printf("END\n")
        state := STOP.U
      }.otherwise {
        state := WADDR.U
      }
    }.elsewhen(state === STOP.U) {
      when (axi.b.fire()) {
        printf("BRESP: id %x\n", axi.b.bits.id)
      }
    }.otherwise {
      printf("Unexpected frontend axi state: %d", state)
    }

    axi.w.valid := wvalid
    axi.aw.valid := awvalid
    axi.w.bits.last := wlast
    axi.aw.bits.id := 0xac.U
    axi.aw.bits.addr := frontBusAccessAddr.U
    axi.aw.bits.len := 0.U // Curr cycle data?
    axi.aw.bits.size := 0.U // 2^2 = 4 bytes
    axi.aw.bits.burst := AXI4Parameters.BURST_INCR
    axi.aw.bits.lock := UInt(0)
    axi.aw.bits.cache := UInt(0)
    axi.aw.bits.prot := AXI4Parameters.PROT_PRIVILEDGED
    axi.aw.bits.qos := UInt(0)
    axi.w.bits.data := UInt(0xdeadbeefL)
    axi.w.bits.strb := UInt(0x2) // only lower 4 bits is allowed to be 1.
  }
}
