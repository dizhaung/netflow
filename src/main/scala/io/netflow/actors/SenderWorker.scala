package io.netflow.actors

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

import com.websudos.phantom.Implicits._
import io.netflow.flows._
import io.netflow.lib._
import io.wasted.util._
import org.joda.time.DateTime

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent._

private[netflow] class SenderWorker(config: FlowSenderRecord) extends Wactor with Logger {
  override protected def loggerName = config.ip.getHostAddress

  private[actors] val senderPrefixes = new AtomicReference(config.prefixes)
  private[actors] val thruputPrefixes = new AtomicReference(config.thruputPrefixes)

  private var templateCache: Map[Int, cflow.Template] = {
    val v9templates = Await.result(cflow.NetFlowV9Template.select.where(_.sender eqs config.ip).fetch(), 30 seconds)
      .map(x => x.number -> x).toMap
    v9templates
  }
  info("Starting up with templates: " + templateCache.keys.mkString(", "))

  def templates = templateCache
  def setTemplate(tmpl: cflow.Template): Unit = templateCache += tmpl.number -> tmpl
  private var cancellable = Shutdown.schedule()

  private def handleFlowPacket(osender: InetSocketAddress, handled: Option[FlowPacket]) = handled match {
    case Some(fp) =>
      fp.persist()
      FlowSender.update.where(_.ip eqs config.ip).
        modify(_.last setTo Some(DateTime.now)).future()
      FlowManager.save(osender, fp, senderPrefixes.get.toList, thruputPrefixes.get.toList)
    case _ =>
      warn("Unable to parse FlowPacket")
      FlowManager.bad(osender)
  }

  def receive = {
    case NetFlow(osender, buf) =>
      Shutdown.avoid()
      val handled: Option[FlowPacket] = {
        Tryo(buf.getUnsignedShort(0)) match {
          case Some(1) => cflow.NetFlowV1Packet(osender, buf).toOption
          case Some(5) => cflow.NetFlowV5Packet(osender, buf).toOption
          case Some(6) => cflow.NetFlowV6Packet(osender, buf).toOption
          case Some(7) => cflow.NetFlowV7Packet(osender, buf).toOption
          case Some(9) => cflow.NetFlowV9Packet(osender, buf, this).toOption
          case Some(10) =>
            info("We do not handle NetFlow IPFIX yet"); None //Some(cflow.NetFlowV10Packet(sender, buf))
          case _ => None
        }
      }
      buf.release()
      handleFlowPacket(osender, handled)

    case SFlow(osender, buf) =>
      Shutdown.avoid()
      if (buf.readableBytes < 28) {
        warn("Unable to parse FlowPacket")
        FlowManager.bad(osender)
      } else {
        val handled: Option[FlowPacket] = {
          Tryo(buf.getLong(0)) match {
            case Some(3) =>
              info("We do not handle sFlow v3 yet"); None // sFlow 3
            case Some(4) =>
              info("We do not handle sFlow v4 yet"); None // sFlow 4
            case Some(5) =>
              //sflow.SFlowV5Packet(sender, buf)
              info("We do not handle sFlow v5 yet"); None // sFlow 5
            case _ => None
          }
        }
        handleFlowPacket(osender, handled)
      }
      buf.release()

    case Shutdown =>
      info("Shutting down")
      SenderManager.removeActorFor(config.ip)
      templateCache = Map.empty
      this ! Wactor.Die
  }

  private case object Shutdown {
    def schedule() = scheduleOnce(Shutdown, 5.minutes)
    def avoid() {
      cancellable.cancel()
      cancellable = schedule()
    }
  }
}
