/*
 * Copyright (C) 2011 Mathias Doenitz
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cc.spray.can

import org.slf4j.LoggerFactory
import akka.actor.Actor
import java.nio.channels.spi.SelectorProvider
import java.nio.ByteBuffer
import java.nio.channels.{SocketChannel, SelectionKey, ServerSocketChannel}
import java.io.IOException
import annotation.tailrec
import akka.dispatch.{ThreadBasedDispatcher, ExecutorBasedEventDrivenDispatcher, Dispatchers}
import akka.dispatch.UnboundedMailbox

// public messages
case object GetServerStats
case class ServerStats(uptime: Long, requestsDispatched: Long)

// private messages
private[can] case object Select
private[can] case class Respond(key: SelectionKey, rawResponse: List[ByteBuffer])

class HttpServer(config: CanConfig) extends Actor with ResponsePreparer {
  private lazy val log = LoggerFactory.getLogger(getClass)
  private lazy val selector = SelectorProvider.provider.openSelector
  private lazy val serverSocketChannel = make(ServerSocketChannel.open) { channel =>
    channel.configureBlocking(false)
    channel.socket.bind(config.endpoint)
    channel.register(selector, SelectionKey.OP_ACCEPT)
  }
  private lazy val serviceActor = Actor.registry.actorsFor(config.serviceActorId) match {
    case Array(head) => head
    case x => throw new RuntimeException("Expected exactly one service actor with id '" +
            config.serviceActorId + "', but found " + x.length)
  }
  private val readBuffer = ByteBuffer.allocateDirect(config.readBufferSize)
  private var startTime: Long = _
  private var requestsDispatched: Long = _

  // this actor runs in its own private thread
  self.dispatcher = new ExecutorBasedEventDrivenDispatcher(
    _name = "spray-can",
    throughput = 10,
    throughputDeadlineTime = -1,
    mailboxType = UnboundedMailbox(),
    config = ThreadBasedDispatcher.oneThread
  )
  self.id = config.serverActorId

  override def preStart() {
    log.info("Starting main event loop")
    serverSocketChannel // trigger serverSocketChannel initialization and registration
    self ! Select
    startTime = System.currentTimeMillis()
  }

  override def preRestart(reason: Throwable, message: Option[Any]) {
    log.error("HttpServer crashed, about to restart...\nmessage: {}\nreason: {}", message.getOrElse("None"), reason)
    cleanUp()
  }

  override def postStop() {
    log.info("Stopped main event loop")
    cleanUp()
  }

  private def cleanUp() {
    selector.close()
    serverSocketChannel.close()
  }

  protected def receive = {
    case Select => {
      select()
      self ! Select // loop
    }
    case Respond(key, rawResponse) => {
      log.debug("Received raw response, scheduling write")
      key.interestOps(SelectionKey.OP_WRITE)
      key.attach(rawResponse)
    }
    case GetServerStats => self.reply {
      ServerStats(System.currentTimeMillis() - startTime, requestsDispatched)
    }
  }

  private def select() {
    def accept() {
      log.debug("Accepting new connection")
      val socketChannel = serverSocketChannel.accept
      socketChannel.configureBlocking(false)
      val key = socketChannel.register(selector, SelectionKey.OP_READ)
      key.attach(EmptyRequestParser)
      log.debug("New connection accepted and registered")
    }

    def read(key: SelectionKey) {
      log.debug("Reading from connection")
      val channel = key.channel.asInstanceOf[SocketChannel]
      val requestParser = key.attachment.asInstanceOf[IntermediateParser]

      def respond(response: HttpResponse) {
        // this code is executed from the thread sending the response
        self ! Respond(key, prepare(response))
        selector.wakeup() // the SelectActor is probably blocked at the "selector.select()" call, so wake it up
      }

      def dispatch(request: CompleteRequestParser) {
        log.debug("Dispatching request to service actor")
        import request._
        serviceActor ! HttpRequest(method, uri, headers.reverse, body, channel.socket.getInetAddress, respond)
        requestsDispatched += 1
      }

      def respondWithError(error: ErrorRequestParser) {
        log.debug("Responding with error response")
        respond(HttpResponse(error.responseStatus, Nil, (error.message + ":\n").getBytes(US_ASCII)))
      }

      def close() {
        key.cancel()
        channel.close()
      }

      try {
        readBuffer.clear()
        if (channel.read(readBuffer) > -1) {
          readBuffer.flip()

          log.debug("Read {} bytes", readBuffer.limit())

          key.attach {
            requestParser.read(readBuffer) match {
              case x: CompleteRequestParser => dispatch(x); EmptyRequestParser
              case x: ErrorRequestParser => respondWithError(x); EmptyRequestParser
              case x => x
            }
          }
        } else {
          log.debug("Closing connection")
          close()
        } // if the client shut down the socket cleanly, we do the same
      }
      catch {
        case e: IOException => {
          // the client forcibly closed the connection
          log.debug("Closing connection due to {}", e)
          close()
        }
      }
    }

    def write(key: SelectionKey) {
      log.debug("Writing to connection")
      val channel = key.channel.asInstanceOf[SocketChannel]
      val rawResponse = key.attachment.asInstanceOf[List[ByteBuffer]]

      @tailrec
      def writeToChannel(buffers: List[ByteBuffer]): List[ByteBuffer] = {
        if (!buffers.isEmpty) {
          channel.write(buffers.head)
          if (buffers.head.remaining == 0) {
            // if we were able to write the whole buffer
            writeToChannel(buffers.tail) // we continue with the next buffer
          } else {
            buffers
          } // otherwise we cannot drop the head and need to continue with it next time
        } else {
          Nil
        }
      }

      writeToChannel(rawResponse) match {
        case Nil => // we were able to write everything, so we can switch back to reading
          key.interestOps(SelectionKey.OP_READ)
          key.attach(EmptyRequestParser)
        case remainingBuffers => // socket buffer full, we couldn't write everything so we stay in writing mode
          key.attach(remainingBuffers)
      }
    }

    selector.select(config.selectionTimeout)
    val selectedKeys = selector.selectedKeys.iterator
    while (selectedKeys.hasNext) {
      val key = selectedKeys.next
      selectedKeys.remove()
      if (key.isValid) {
        if (key.isAcceptable) accept()
        else if (key.isReadable) read(key)
        else if (key.isWritable) write(key)
      } else {
        log.warn("Invalid selection key: {}", key)
      }
    }
  }
}