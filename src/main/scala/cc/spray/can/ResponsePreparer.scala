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

import java.nio.ByteBuffer
import annotation.tailrec
import utils.DateTime

trait ResponsePreparer {

  protected def prepare(response: HttpResponse): List[ByteBuffer] = {
    import Constants._
    import ByteBuffer._

    def statusLine(rest: List[ByteBuffer]) = response.statusCode match {
      case 200 => wrap(StatusLine200) :: rest
      case x => {
        wrap(HttpVersionPlusSP) ::
          wrap(response.statusCode.toString.getBytes(US_ASCII)) ::
            wrap(SingleSP) ::
              wrap(HttpResponse.defaultReason(response.statusCode).getBytes(US_ASCII)) ::
                wrap(CRLF) :: rest
      }
    }
    def header(name: String, value: String)(rest: List[ByteBuffer]) = {
      wrap(name.getBytes(US_ASCII)) ::
        wrap(ColonSP) ::
          wrap(value.getBytes(US_ASCII)) ::
            wrap(CRLF) :: rest
    }
    @tailrec
    def headers(httpHeaders: List[HttpHeader])(rest: List[ByteBuffer]): List[ByteBuffer] = httpHeaders match {
      case HttpHeader(name, value) :: tail => headers(tail)(header(name, value)(rest))
      case Nil => rest
    }

    def contentLengthHeader(rest: List[ByteBuffer]) =
      if (response.body.length > 0) header("Content-Length", response.body.length.toString)(rest) else rest

    statusLine {
      headers(response.headers.reverse) {
        contentLengthHeader {
          header("Date", now.toRfc1123DateTimeString) {
            wrap(CRLF) ::
              wrap(response.body) :: Nil
          }
        }
      }
    }
  }

  protected def now = DateTime.now

}