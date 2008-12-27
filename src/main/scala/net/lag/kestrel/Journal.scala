/*
 * Copyright (c) 2008 Robey Pointer <robeypointer@lag.net>
 *
 * Permission is hereby granted, free of charge, to any person obtaining a
 * copy of this software and associated documentation files (the "Software"),
 * to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense,
 * and/or sell copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL
 * THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
 */

package net.lag.kestrel

import net.lag.logging.Logger
import java.io._
import java.nio.{ByteBuffer, ByteOrder}
import java.nio.channels.FileChannel


// returned from journal replay
abstract case class JournalItem
object JournalItem {
  case class Add(item: QItem) extends JournalItem
  case object Remove extends JournalItem
  case object RemoveTentative extends JournalItem
  case class SavedXid(xid: Int) extends JournalItem
  case class Unremove(xid: Int) extends JournalItem
  case class ConfirmRemove(xid: Int) extends JournalItem
  case object EndOfFile extends JournalItem
}


/**
 * Codes for working with the journal file for a PersistentQueue.
 */
class Journal(queuePath: String) {

  /* in theory, you might want to sync the file after each
   * transaction. however, the original starling doesn't.
   * i think if you can cope with a truncated journal file,
   * this is fine, because a non-synced file only matters on
   * catastrophic disk/machine failure.
   */

  private val log = Logger.get

  private var writer: FileChannel = null
  private var reader: Option[FileChannel] = None
  private var replayer: Option[FileChannel] = None

  var size: Long = 0

  // small temporary buffer for formatting operations into the journal:
  private val buffer = new Array[Byte](16)
  private val byteBuffer = ByteBuffer.wrap(buffer)
  byteBuffer.order(ByteOrder.LITTLE_ENDIAN)

  private val CMD_ADD = 0
  private val CMD_REMOVE = 1
  private val CMD_ADDX = 2
  private val CMD_REMOVE_TENTATIVE = 3
  private val CMD_SAVE_XID = 4
  private val CMD_UNREMOVE = 5
  private val CMD_CONFIRM_REMOVE = 6


  def open(): Unit = {
    writer = new FileOutputStream(queuePath, true).getChannel
  }

  def roll(): Unit = {
    writer.close
    val backupFile = new File(queuePath + "." + Time.now)
    new File(queuePath).renameTo(backupFile)
    open
    size = 0
    backupFile.delete
  }

  def close(): Unit = {
    writer.close
    for (r <- reader) r.close
    reader = None
  }

  def inReadBehind(): Boolean = reader.isDefined

  def add(item: QItem) = {
    val blob = ByteBuffer.wrap(pack(item))
    byteBuffer.clear
    byteBuffer.put(CMD_ADDX.toByte)
    byteBuffer.putInt(blob.limit)
    byteBuffer.flip
    do {
      writer.write(byteBuffer)
    } while (byteBuffer.position < byteBuffer.limit)
    do {
      writer.write(blob)
    } while (blob.position < blob.limit)
    size += (5 + blob.limit)
  }

  def remove() = {
    byteBuffer.clear
    byteBuffer.put(CMD_REMOVE.toByte)
    byteBuffer.flip
    while (byteBuffer.position < byteBuffer.limit) {
      writer.write(byteBuffer)
    }
    size += 1
  }

  def removeTentative() = {
    byteBuffer.clear
    byteBuffer.put(CMD_REMOVE_TENTATIVE.toByte)
    byteBuffer.flip
    while (byteBuffer.position < byteBuffer.limit) {
      writer.write(byteBuffer)
    }
    size += 1
  }

  def saveXid(xid: Int) = {
    byteBuffer.clear
    byteBuffer.put(CMD_SAVE_XID.toByte)
    byteBuffer.putInt(xid)
    byteBuffer.flip
    while (byteBuffer.position < byteBuffer.limit) {
      writer.write(byteBuffer)
    }
    size += 5
  }

  def unremove(xid: Int) = {
    byteBuffer.clear
    byteBuffer.put(CMD_UNREMOVE.toByte)
    byteBuffer.putInt(xid)
    byteBuffer.flip
    while (byteBuffer.position < byteBuffer.limit) {
      writer.write(byteBuffer)
    }
    size += 5
  }

  def confirmRemove(xid: Int) = {
    byteBuffer.clear
    byteBuffer.put(CMD_CONFIRM_REMOVE.toByte)
    byteBuffer.putInt(xid)
    byteBuffer.flip
    while (byteBuffer.position < byteBuffer.limit) {
      writer.write(byteBuffer)
    }
    size += 5
  }

  def startReadBehind(): Unit = {
    val pos = if (replayer.isDefined) replayer.get.position else writer.position
    val rj = new FileInputStream(queuePath).getChannel
    rj.position(pos)
    reader = Some(rj)
  }

  def fillReadBehind(f: QItem => Unit): Unit = {
    val pos = if (replayer.isDefined) replayer.get.position else writer.position
    for (rj <- reader) {
      if (rj.position == pos) {
        // we've caught up.
        rj.close
        reader = None
      } else {
        readJournalEntry(rj, false) match {
          case JournalItem.Add(item) => f(item)
          case _ =>
        }
      }
    }
  }

  def replay(name: String)(f: JournalItem => Unit): Unit = {
    size = 0
    try {
      val in = new FileInputStream(queuePath).getChannel
      replayer = Some(in)
      var done = false
      do {
        readJournalEntry(in, true) match {
          case JournalItem.EndOfFile => done = true
          case x: JournalItem => f(x)
        }
      } while (!done)
    } catch {
      case e: FileNotFoundException =>
        log.info("No transaction journal for '%s'; starting with empty queue.", name)
      case e: IOException =>
        log.error(e, "Exception replaying journal for '%s'", name)
        log.error("DATA MAY HAVE BEEN LOST!")
        // this can happen if the server hardware died abruptly in the middle
        // of writing a journal. not awesome but we should recover.
    }
    replayer = None
  }

  private def readJournalEntry(in: FileChannel, replaying: Boolean): JournalItem = {
    byteBuffer.rewind
    byteBuffer.limit(1)
    var x: Int = 0
    do {
      x = in.read(byteBuffer)
    } while (byteBuffer.position < byteBuffer.limit && x >= 0)

    if (x < 0) {
      JournalItem.EndOfFile
    } else {
      buffer(0) match {
        case CMD_ADD =>
          val data = readBlock(in)
          if (replaying) size += 5 + data.length
          JournalItem.Add(unpackOldAdd(data))
        case CMD_REMOVE =>
          if (replaying) size += 1
          JournalItem.Remove
        case CMD_ADDX =>
          val data = readBlock(in)
          if (replaying) size += 5 + data.length
          JournalItem.Add(unpack(data))
        case CMD_REMOVE_TENTATIVE =>
          if (replaying) size += 1
          JournalItem.RemoveTentative
        case CMD_SAVE_XID =>
          val xid = readInt(in)
          if (replaying) size += 5
          JournalItem.SavedXid(xid)
        case CMD_UNREMOVE =>
          val xid = readInt(in)
          if (replaying) size += 5
          JournalItem.Unremove(xid)
        case CMD_CONFIRM_REMOVE =>
          val xid = readInt(in)
          if (replaying) size += 5
          JournalItem.ConfirmRemove(xid)
        case n =>
          throw new IOException("invalid opcode in journal: " + n.toInt)
      }
    }
  }

  private def readBlock(in: FileChannel): Array[Byte] = {
    val size = readInt(in)
    val data = new Array[Byte](size)
    val dataBuffer = ByteBuffer.wrap(data)
    var x: Int = 0
    do {
      x = in.read(dataBuffer)
    } while (dataBuffer.position < dataBuffer.limit && x >= 0)
    if (x < 0) {
      // we never expect EOF when reading a block.
      throw new IOException("Unexpected EOF")
    }
    data
  }

  private def readInt(in: FileChannel): Int = {
    byteBuffer.rewind
    byteBuffer.limit(4)
    var x: Int = 0
    do {
      x = in.read(byteBuffer)
    } while (byteBuffer.position < byteBuffer.limit && x >= 0)
    if (x < 0) {
      // we never expect EOF when reading an int.
      throw new IOException("Unexpected EOF")
    }
    byteBuffer.rewind
    byteBuffer.getInt()
  }

  private def pack(item: QItem): Array[Byte] = {
    val bytes = new Array[Byte](item.data.length + 16)
    val buffer = ByteBuffer.wrap(bytes)
    buffer.order(ByteOrder.LITTLE_ENDIAN)
    buffer.putLong(item.addTime)
    buffer.putLong(item.expiry)
    buffer.put(item.data)
    bytes
  }

  private def unpack(data: Array[Byte]): QItem = {
    val buffer = ByteBuffer.wrap(data)
    val bytes = new Array[Byte](data.length - 16)
    buffer.order(ByteOrder.LITTLE_ENDIAN)
    val addTime = buffer.getLong
    val expiry = buffer.getLong
    buffer.get(bytes)
    return QItem(addTime, expiry, bytes, 0)
  }

  private def unpackOldAdd(data: Array[Byte]): QItem = {
    val buffer = ByteBuffer.wrap(data)
    val bytes = new Array[Byte](data.length - 4)
    buffer.order(ByteOrder.LITTLE_ENDIAN)
    val expiry = buffer.getInt
    buffer.get(bytes)
    return QItem(Time.now, if (expiry == 0) 0 else expiry * 1000, bytes, 0)
  }
}
