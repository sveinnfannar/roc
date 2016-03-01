package com.github.finagle
package roc
package postgresql

import cats.data.Xor
import com.github.finagle.roc.postgresql.transport.{Buffer, BufferReader, Packet}
import scala.collection.mutable.ListBuffer

private[postgresql] trait PacketDecoder[A <: BackendMessage] {
  def apply(p: Packet): PacketDecoder.Result[A]
}
object PacketDecoder {
  final type Result[A] = Xor[Error, A]
}

trait PacketDecoderImplicits {
  import PacketDecoder._

  implicit val errorMessagePacketDecoder: PacketDecoder[ErrorResponse] = 
    new PacketDecoder[ErrorResponse] {
      def apply(p: Packet): Result[ErrorResponse] =
        Xor.Left(new PacketDecodingFailure("Error messages not implemented yet"))
    }

  implicit val commandCompletePacketDecoder: PacketDecoder[CommandComplete] = 
    new PacketDecoder[CommandComplete] {
      def apply(p: Packet): Result[CommandComplete] = Xor.catchNonFatal({
        val br = BufferReader(p.body)
        val commandTag = br.readNullTerminatedString()
        new CommandComplete(commandTag)
      }).leftMap(t => new PacketDecodingFailure(t.getMessage))
    }

  implicit val parameterStatusPacketDecoder: PacketDecoder[ParameterStatus] =
    new PacketDecoder[ParameterStatus] {
      def apply(p: Packet): Result[ParameterStatus] = Xor.catchNonFatal({
        val br = BufferReader(p.body)
        val param = br.readNullTerminatedString()
        val value = br.readNullTerminatedString()
        new ParameterStatus(param, value)
      }).leftMap(t => new PacketDecodingFailure(t.getMessage))
    }

  implicit val backendKeyDataPacketDecoder: PacketDecoder[BackendKeyData] = 
    new PacketDecoder[BackendKeyData] {
      def apply(p: Packet): Result[BackendKeyData] = Xor.catchNonFatal({
        val br        = BufferReader(p.body)
        val processId = br.readInt
        val secretKey = br.readInt
        new BackendKeyData(processId, secretKey)
      }).leftMap(t => new PacketDecodingFailure(t.getMessage))
    }

  implicit val readyForQueryPacketDecoder: PacketDecoder[ReadyForQuery] =
    new PacketDecoder[ReadyForQuery] {
      def apply(p: Packet): Result[ReadyForQuery] = Xor.catchNonFatal({
        val br   = BufferReader(p.body)
        val byte = br.readByte
        byte.toChar
      })
      .leftMap(t => new PacketDecodingFailure(t.getMessage))
      .flatMap(ReadyForQuery(_))
    }

  implicit val rowDescriptionPacketDecoder: PacketDecoder[RowDescription] = 
    new PacketDecoder[RowDescription] {
      def apply(p: Packet): Result[RowDescription] = Xor.catchNonFatal({
        val br        = BufferReader(p.body)
        val numFields = br.readShort

        @annotation.tailrec
        def loop(currCount: Short, fs: List[RowDescriptionField]): List[RowDescriptionField] =
          currCount match {
            case x if x < numFields => {
              val name = br.readNullTerminatedString()
              val tableObjectId = br.readInt
              val tableAttributeId = br.readShort 
              val dataTypeObjectId = br.readInt
              val dataTypeSize = br.readShort
              val typeModifier = br.readInt
              val formatCode = br.readShort match {
                case 0 => Text
                case 1 => Binary
                case _ => throw new Exception()
              }

              val rdf = RowDescriptionField(name, tableObjectId, tableAttributeId, dataTypeObjectId,
                dataTypeSize, typeModifier, formatCode)
              loop((currCount + 1).toShort, rdf :: fs)
            }
            case x if x >= numFields => fs
          }

        val fs = loop(0, List.empty[RowDescriptionField]).reverse
        RowDescription(numFields, fs)
      }).leftMap(t => new PacketDecodingFailure(t.getMessage))
    }

    implicit val dataRowPacketDecoder: PacketDecoder[DataRow] = new PacketDecoder[DataRow] {
      def apply(p: Packet): Result[DataRow] = Xor.catchNonFatal({
        val br = BufferReader(p.body)
        val columns = br.readShort

        @annotation.tailrec
        def loop(idx: Short, cbs: ListBuffer[Option[Array[Byte]]]): List[Option[Array[Byte]]] = 
          idx match {
            case x if x < columns => {
              val columnLength = br.readInt
              val bytes = if(columnLength == -1) {
                None
              } else if(columnLength == 0) {
                Some(Array.empty[Byte])
              } else {
                Some(br.take(columnLength))
              }
              loop((idx + 1).toShort, cbs += bytes)
            }
            case x if x >= columns => cbs.toList
          }

        val columnBytes = loop(0, ListBuffer.empty[Option[Array[Byte]]])
        new DataRow(columns, columnBytes)
      }).leftMap(t => new PacketDecodingFailure(t.getMessage))
    }

  implicit val authenticationMessagePacketDecoder: PacketDecoder[AuthenticationMessage] = 
    new PacketDecoder[AuthenticationMessage] {
      def apply(p: Packet): Result[AuthenticationMessage] = Xor.catchNonFatal({
        val br = BufferReader(p.body)
        br.readInt match {
          case 0 => (0, None)
          case 2 => (2, None)
          case 3 => (3, None)
          case 5 => (5, Some(br.take(4)))
          case 6 => (6, None)
          case 7 => (7, None)
          case 8 => (8, Some(br.takeRest))
          case 9 => (9, None)
          case n => (n, None)
        }
      })
      .leftMap(t => new PacketDecodingFailure(t.getMessage))
      .flatMap(AuthenticationMessage(_))
    }
}