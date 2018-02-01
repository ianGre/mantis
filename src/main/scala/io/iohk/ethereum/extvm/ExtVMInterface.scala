package io.iohk.ethereum.extvm

import java.nio.ByteOrder

import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.stream.scaladsl.{Framing, Keep, Sink, SinkQueueWithCancel, Source, SourceQueueWithComplete, Tcp}
import akka.util.ByteString
import io.iohk.ethereum.utils.BlockchainConfig
import io.iohk.ethereum.vm._

import scala.annotation.tailrec
import scala.util.{Failure, Success, Try}

class ExtVMInterface(host: String, port: Int, blockchainConfig: BlockchainConfig)(implicit system: ActorSystem) extends VM {

  private implicit val materializer = ActorMaterializer()

  private var out: Option[SourceQueueWithComplete[ByteString]] = None

  private var in: Option[SinkQueueWithCancel[ByteString]] = None

  private var vmClient: Option[VMClient] = None

  initConnection()

  private def initConnection(): Unit = {
    close()

    val connection = Tcp().outgoingConnection(host, port)

    val (connOut, connIn) = Source.queue[ByteString](QueueBufferSize, OverflowStrategy.dropTail)
      .via(connection)
      .via(Framing.lengthField(LengthPrefixSize, 0, Int.MaxValue, ByteOrder.BIG_ENDIAN))
      .map(_.drop(4))
      .toMat(Sink.queue[ByteString]())(Keep.both)
      .run()

    out = Some(connOut)
    in = Some(connIn)

    val client = new VMClient(connIn, connOut)
    client.setBlockchainConfig(blockchainConfig)

    vmClient = Some(client)
  }

  @tailrec
  override final def run[W <: WorldStateProxy[W, S], S <: Storage[S]](context: ProgramContext[W, S]): ProgramResult[W, S] = {
    if (vmClient.isEmpty) initConnection()

    Try(vmClient.get.run(context)) match {
      case Success(res) => res
      case Failure(ex) =>
        ex.printStackTrace()
        initConnection()
        run(context)
    }
  }

  def close(): Unit = {
    vmClient.foreach(_.close())
    vmClient = None
  }

}