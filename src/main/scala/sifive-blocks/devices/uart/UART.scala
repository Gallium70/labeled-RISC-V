// See LICENSE for license details.
package sifive.blocks.devices.uart

import Chisel._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.config._
import freechips.rocketchip.regmapper._
import freechips.rocketchip.util._
import sifive.blocks.util.{NonBlockingEnqueue, NonBlockingDequeue}

case class UARTParams(
  address: BigInt,
  dataBits: Int = 8,
  stopBits: Int = 1,
  divisorBits: Int = 16,
  oversample: Int = 4,
  nSamples: Int = 3,
  nTxEntries: Int = 8,
  nRxEntries: Int = 8)

trait HasUARTParameters {
  def c: UARTParams
  def uartDataBits = c.dataBits
  def uartStopBits = c.stopBits
  def uartDivisorBits = c.divisorBits

  def uartOversample = c.oversample
  def uartOversampleFactor = 1 << uartOversample
  def uartNSamples = c.nSamples

  def uartNTxEntries = c.nTxEntries
  def uartNRxEntries = c.nRxEntries

  require(uartDivisorBits > uartOversample)
  require(uartOversampleFactor > uartNSamples)
}

abstract class UARTModule(val c:UARTParams)(implicit val p: Parameters)
    extends Module with HasUARTParameters

class UARTPortIO extends Bundle {
  val txd = Bool(OUTPUT)
  val rxd = Bool(INPUT)
}

trait HasUARTTopBundleContents extends Bundle {
  val port = new UARTPortIO
}

class UARTTx(c: UARTParams)(implicit p: Parameters) extends UARTModule(c)(p) {
  val io = new Bundle {
    val en = Bool(INPUT)
    val in = Decoupled(Bits(width = uartDataBits)).flip
    val out = Bits(OUTPUT, 1)
    val div = UInt(INPUT, uartDivisorBits)
    val nstop = UInt(INPUT, log2Up(uartStopBits))
  }

  val prescaler = Reg(init = UInt(0, uartDivisorBits))
  val pulse = (prescaler === UInt(0))

  private val n = uartDataBits + 1
  val counter = Reg(init = UInt(0, log2Floor(n + uartStopBits) + 1))
  val shifter = Reg(Bits(width = n))
  val out = Reg(init = Bits(1, 1))
  io.out := out

  val busy = (counter =/= UInt(0))
  io.in.ready := io.en && !busy
  when (io.in.fire()) {
    shifter := Cat(io.in.bits, Bits(0, 1))
    counter := Mux1H((0 until uartStopBits).map(i =>
      (io.nstop === UInt(i)) -> UInt(n + i + 1)))
  }
  when (busy) {
    prescaler := Mux(pulse, io.div, prescaler - UInt(1))
  }
  when (pulse && busy) {
    counter := counter - UInt(1)
    shifter := Cat(Bits(1, 1), shifter >> 1)
    out := shifter(0)
  }
}

class UARTRx(c: UARTParams)(implicit p: Parameters) extends UARTModule(c)(p) {
  val io = new Bundle {
    val en = Bool(INPUT)
    val in = Bits(INPUT, 1)
    val out = Valid(Bits(width = uartDataBits))
    val div = UInt(INPUT, uartDivisorBits)
  }

  val debounce = Reg(init = UInt(0, 2))
  val debounce_max = (debounce === UInt(3))
  val debounce_min = (debounce === UInt(0))

  val prescaler = Reg(init = UInt(0, uartDivisorBits - uartOversample))
  val start = Wire(init = Bool(false))
  val busy = Wire(init = Bool(false))
  val pulse = (prescaler === UInt(0)) && busy

  when (busy) {
    prescaler := prescaler - UInt(1)
  }
  when (start || pulse) {
    prescaler := io.div >> uartOversample
  }

  val sample = Reg(Bits(width = uartNSamples))
  val voter = Majority(sample.toBools.toSet)
  when (pulse) {
    sample := Cat(sample, io.in)
  }

  private val delay0 = (uartOversampleFactor + uartNSamples) >> 1
  private val delay1 = uartOversampleFactor

  val timer = Reg(UInt(width = uartOversample + 1))
  val counter = Reg(UInt(width = log2Floor(uartDataBits) + 1))
  val shifter = Reg(Bits(width = uartDataBits))
  val expire = (timer === UInt(0)) && pulse

  val sched = Wire(init = Bool(false))
  when (pulse) {
    timer := timer - UInt(1)
  }
  when (sched) {
    timer := UInt(delay1-1)
  }

  val valid = Reg(init = Bool(false))
  valid := Bool(false)
  io.out.valid := valid
  io.out.bits := shifter

  val (s_idle :: s_start :: s_data :: Nil) = Enum(UInt(), 3)
  val state = Reg(init = s_idle)

  switch (state) {
    is (s_idle) {
      when (!(!io.in) && !debounce_min) {
        debounce := debounce - UInt(1)
      }
      when (!io.in) {
        debounce := debounce + UInt(1)
        when (debounce_max) {
          state := s_start
          start := Bool(true)
          timer := UInt(delay0-1)
        }
      }
    }

    is (s_start) {
      busy := Bool(true)
      when (expire) {
        sched := Bool(true)
        when (voter) {
          state := s_idle
        } .otherwise {
          state := s_data
          counter := UInt(uartDataBits)
        }
      }
    }

    is (s_data) {
      busy := Bool(true)
      when (expire) {
        counter := counter - UInt(1)
        when (counter === UInt(0)) {
          state := s_idle
          valid := Bool(true)
        } .otherwise {
          shifter := Cat(voter, shifter >> 1)
          sched := Bool(true)
        }
      }
    }
  }

  when (!io.en) {
    debounce := UInt(0)
  }
}

class UARTInterrupts extends Bundle {
  val rxwm = Bool()
  val txwm = Bool()
}

trait HasUARTTopModuleContents extends HasUARTParameters with HasRegMap {
  val io: HasUARTTopBundleContents

  implicit val p: Parameters
  def params: UARTParams
  def c = params

  val txm = Module(new UARTTx(params))
  val txq = Module(new Queue(txm.io.in.bits, uartNTxEntries))

  val rxm = Module(new UARTRx(params))
  val rxq = Module(new Queue(rxm.io.out.bits, uartNRxEntries))

  val divinit = 2 // 434 // (50MHz / 115200)
  val div = Reg(init = UInt(divinit, uartDivisorBits))

  private val stopCountBits = log2Up(uartStopBits)
  private val txCountBits = log2Floor(uartNTxEntries) + 1
  private val rxCountBits = log2Floor(uartNRxEntries) + 1

  val txen = Reg(init = Bool(true))
  val rxen = Reg(init = Bool(true))
  val txwm = Reg(init = UInt(0, txCountBits))
  val rxwm = Reg(init = UInt(0, rxCountBits))
  val nstop = Reg(init = UInt(0, stopCountBits))

  txm.io.en := txen
  txm.io.in <> txq.io.deq
  txm.io.div := div
  txm.io.nstop := nstop
  io.port.txd := txm.io.out

  // printer is wrapped in a module, so that we can provide a clock to it
  // here, in this trait, we can not give it a clock
  // since if we extends Module, we will get an Scala inheritance error when TLRegModule tries to extends it
  val printer = Module(new UARTPrinterWrapper(s"serial${params.address.toInt.toHexString}"))
  printer.io.valid := txm.io.in.fire()
  printer.io.data := txm.io.in.bits

  rxm.io.en := rxen
  // disable rx for emulator
  rxm.io.in := Bool(true)
  rxq.io.enq <> rxm.io.out
  rxm.io.div := div

  // val ie = Reg(init = new UARTInterrupts().fromBits(Bits(0)))
  val ie = Reg(init = Bool(false))
  val ip = Wire(new UARTInterrupts)

  // ip.txwm := (txq.io.count < txwm)
  ip.txwm := (txq.io.count === UInt(0))
  ip.rxwm := (rxq.io.count > rxwm)

  // interrupts(0) := Bool(false) //(ip.txwm && ie.txwm) || (ip.rxwm && ie.rxwm)
  interrupts(0) := (ip.txwm || ip.rxwm) && ie
  val tracer = Module(new Tracer(s"uart_intr@${c.address}"))
  tracer.io.signal := interrupts(0)
  // val tracer2 = Module(new Tracer("ie"))
  // val tracer3 = Module(new Tracer("txwm"))
  // tracer2.io.signal := ie
  // tracer3.io.signal := ip.txwm

  regmap(
    UARTCtrlRegs.txfifo -> NonBlockingEnqueue(txq.io.enq),
    UARTCtrlRegs.rxfifo -> NonBlockingDequeue(rxq.io.deq),

    UARTCtrlRegs.stat -> Seq(
      RegField.r(1, rxq.io.count =/= UInt(0)),
      RegField.r(1, rxq.io.count === UInt(uartNRxEntries)),
      RegField.r(1, txq.io.count === UInt(0)),
      RegField.r(1, txq.io.count === UInt(uartNTxEntries)),
      RegField.r(1, UInt(0)),
      RegField.r(1, UInt(0)),
      RegField.r(1, UInt(0)),
      RegField.r(1, UInt(0))
    ),

  UARTCtrlRegs.ctrl -> Seq(
    RegField.r(1, UInt(0)),
    RegField.r(1, UInt(0)),
    RegField.r(2, UInt(0)),
    // RegField.r(1, UInt(0))
    RegField.w(1, ie)
  )


/*
UARTCtrlRegs.txctrl -> Seq(
  RegField(1, txen),
  RegField(stopCountBits, nstop)),
UARTCtrlRegs.rxctrl -> Seq(RegField(1, rxen)),
UARTCtrlRegs.txmark -> Seq(RegField(txCountBits, txwm)),
UARTCtrlRegs.rxmark -> Seq(RegField(rxCountBits, rxwm)),

UARTCtrlRegs.ie -> Seq(
  RegField(1, ie.txwm),
  RegField(1, ie.rxwm)),

UARTCtrlRegs.ip -> Seq(
  RegField.r(1, ip.txwm),
  RegField.r(1, ip.rxwm)),

UARTCtrlRegs.div -> Seq(
  RegField(uartDivisorBits, div))
  */
 )
}

// Magic TL2 Incantation to create a TL2 UART
// 这边这个结构啥意思啊？看不懂啊？怎么后面会有那么多的括号？
class TLUART(w: Int, c: UARTParams)(implicit p: Parameters)
  extends TLRegisterRouter(c.address, "serial", Seq("sifive,uart0"), interrupts = 1, beatBytes = w)(
  new TLRegBundle(c, _)    with HasUARTTopBundleContents)(
  new TLRegModule(c, _, _) with HasUARTTopModuleContents)
