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
import java.nio.channels.{SocketChannel, SelectionKey, ServerSocketChannel}
import utils.LinkedList
import HttpProtocols._
import java.util.concurrent.atomic.AtomicBoolean
import java.io.IOException
import akka.actor.PoisonPill
import java.net.InetAddress

// public incoming messages
case object GetServerStats

// public outgoing messages
case class RequestContext(
  request: HttpRequest,
  protocol: HttpProtocol,
  remoteAddress: InetAddress,
  responder: HttpResponse => Unit
)
case class Timeout(context: RequestContext)
case class ServerStats(
  uptime: Long,
  requestsDispatched: Long,
  requestsTimedOut: Long,
  requestsOpen: Int,
  connectionsOpen: Int
)

object HttpServer {
  private case class Respond(connRec: ConnRecord, writeJob: WriteJob)
  private case class TimeoutContext(request: HttpRequest, protocol: HttpProtocol, remoteAddress: InetAddress,
                                    responder: HttpResponse => Unit) extends LinkedList.Element[TimeoutContext]
  private case class CancelTimeout(context: TimeoutContext)
}

class HttpServer(config: ServerConfig = AkkaConfServerConfig) extends HttpPeer(config) with ResponsePreparer {
  import HttpServer._

  private lazy val log = LoggerFactory.getLogger(getClass)
  private lazy val serverSocketChannel = make(ServerSocketChannel.open) { channel =>
    channel.configureBlocking(false)
    channel.socket.bind(config.endpoint)
    channel.register(selector, SelectionKey.OP_ACCEPT)
  }
  private lazy val serviceActor = actor(config.serviceActorId)
  private lazy val timeoutServiceActor = actor(config.timeoutServiceActorId)
  private val openRequests = new LinkedList[TimeoutContext]
  private val openTimeouts = new LinkedList[TimeoutContext]

  self.id = config.serverActorId

  // we use our own custom single-thread dispatcher, because our thread will, for the most time,
  // be blocked at selector selection, therefore we need to wake it up upon message or task arrival
  self.dispatcher = new SelectorWakingDispatcher("spray-can-server", selector)

  override def preStart() {
    log.info("Starting spray-can HTTP server on {}", config.endpoint)
    try {
      serverSocketChannel // trigger serverSocketChannel initialization and registration
      super.preStart()
    } catch {
      case e: IOException => {
        log.error("Could not open and register server socket on {}:\n{}", config.endpoint, e.toString)
        self ! PoisonPill
      }
    }
  }

  override def postStop() {
    log.info("Stopped spray-can HTTP server on {}", config.endpoint)
    super.postStop()
  }

  protected override def cleanUp() {
    super.cleanUp()
    serverSocketChannel.close()
  }

  protected override def receive = super.receive orElse {
    case Respond(connRec, writeJob) => respond(connRec, writeJob)
    case CancelTimeout(ctx) => ctx.memberOf -= ctx // remove from either the openRequests or the openTimeouts list
    case GetServerStats => self.reply(serverStats)
  }

  protected def accept() {
    log.debug("Accepting new connection")
    protectIO("Accept") {
      val socketChannel = serverSocketChannel.accept
      socketChannel.configureBlocking(false)
      val key = socketChannel.register(selector, SelectionKey.OP_READ)
      val connRecord = new ConnRecord(key, load = EmptyRequestParser)
      key.attach(connRecord)
      connections += connRecord
      log.debug("New connection accepted and registered")
    }
  }

  protected def finishConnection(connRec: ConnRecord) {
    throw new IllegalStateException
  }

  protected def readComplete(connRec: ConnRecord, parser: CompleteMessageParser): ConnRecordLoad = {
    import parser._
    val requestLine = messageLine.asInstanceOf[RequestLine]
    import requestLine._
    log.debug("Dispatching {} request to '{}' to the service actor", method, uri)
    val alreadyCompleted = new AtomicBoolean(false)

    def verify(response: HttpResponse) {
      import response._
      require(100 <= status && status < 600, "Illegal HTTP status code: " + status)
      require(headers != null, "headers must not be null")
      require(body != null, "body must not be null (you can use cc.spray.can.EmptyByteArray for an empty body)")
      require(headers.forall(_.name != "Content-Length"), "Content-Length header must not be present, the HttpServer sets it itself")
      require(body.length == 0 || status / 100 > 1 && status != 204 && status != 304, "Illegal HTTP response: " +
              "responses with status code " + status + " must not have a message body")
    }

    def respond(response: HttpResponse): Boolean = {
      verify(response)
      if (alreadyCompleted.compareAndSet(false, true)) {
        log.debug("Received HttpResponse, enqueuing RawResponse")
        self ! Respond(connRec, prepare(response, protocol, connectionHeader))
        true
      } else false
    }

    val httpRequest = HttpRequest(method, uri, headers, body)
    val remoteAddress = connRec.key.channel.asInstanceOf[SocketChannel].socket.getInetAddress
    val responder: HttpResponse => Unit = {
      if (requestTimeoutsEnabled) {
        val timeoutContext = new TimeoutContext(httpRequest, protocol, remoteAddress, { response =>
          if (respond(response)) requestsTimedOut += 1
        })
        openRequests += timeoutContext;
        { response =>
          if (respond(response)) {
            self ! CancelTimeout(timeoutContext)
          } else {
            log.warn("Received an additional response for an already completed request to '{}', ignoring...", httpRequest.uri)
          }
        }
      } else { response =>
          if (!respond(response)) {
            log.warn("Received an additional response for an already completed request to '{}', ignoring...", httpRequest.uri)
        }
      }
    }
    serviceActor ! RequestContext(httpRequest, protocol, remoteAddress, responder)
    requestsDispatched += 1
    requestsOpen += 1
    EmptyRequestParser // switch back to parsing the next request from the start
  }

  protected def readParsingError(connRec: ConnRecord, parser: ErrorMessageParser): ConnRecordLoad = {
    log.debug("Illegal request, responding with status {} and '{}'", parser.status, parser.message)
    val response = HttpResponse(status = parser.status,
      headers = List(HttpHeader("Content-Type", "text/plain"))).withBody(parser.message)
    // In case of a request parsing error we probably stopped reading the request somewhere in between, where we
    // cannot simply resume. Resetting to a known state is not easy either, so we need to close the connection to do so.
    // This is done here by pretending the request contained a "Connection: close" header
    self ! Respond(connRec, prepare(response, `HTTP/1.1`, Some("close")))
    parser // we just need to return some parser,
  }        // it will never be used since we close the connection after writing the error response

  protected def writeComplete(connRec: ConnRecord): ConnRecordLoad = {
    requestsOpen -= 1
    EmptyRequestParser
  }

  protected def handleTimedOutRequests() {
    openRequests.forAllTimedOut(config.requestTimeout) { ctx =>
      log.warn("A request to '{}' timed out, dispatching to the TimeoutService '{}'",
        ctx.request.uri, config.timeoutServiceActorId)
      openRequests -= ctx
      openTimeouts += ctx
      import ctx._
      timeoutServiceActor ! Timeout(RequestContext(request, protocol, remoteAddress, responder))
    }
    openTimeouts.forAllTimedOut(config.timeoutTimeout) { ctx =>
      log.warn("The TimeoutService for '{}' timed out as well, responding with the static error reponse", ctx.request.uri)
      ctx.responder(timeoutTimeoutResponse(ctx.request))
    }
  }

  protected def respond(connRec: ConnRecord, writeJob: WriteJob) {
    if (connRec.key.isValid) {
      log.debug("Received raw response as WriteJob, scheduling write")
      connRec.key.interestOps(SelectionKey.OP_WRITE)
      connRec.load = writeJob
    } else log.warn("Dropping response due to closed connection")
  }

  protected def serverStats = {
    log.debug("Received GetServerStats request, responding with stats")
    ServerStats(System.currentTimeMillis - startTime, requestsDispatched, requestsTimedOut, requestsOpen, connections.size)
  }

  protected def timeoutTimeoutResponse(request: HttpRequest) = {
    HttpResponse(status = 500, headers = List(HttpHeader("Content-Type", "text/plain"))).withBody {
      "Ooops! The server was not able to produce a timely response to your request.\n" +
      "Please try again in a short while!"
    }
  }
}