/*
 * Licensed to Cloudera, Inc. under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  Cloudera, Inc. licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.cloudera.livy.repl

import java.util.concurrent.TimeUnit
import javax.servlet.ServletContext
import javax.servlet.http.HttpServletResponse

import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

import com.ning.http.client.AsyncHttpClient
import org.json4s.{DefaultFormats, Formats}
import org.json4s.jackson.Serialization.write
import org.scalatra.LifeCycle
import org.scalatra.servlet.ScalatraListener

import com.cloudera.livy.{LivyConf, Logging, Utils, WebServer}
import com.cloudera.livy.repl.python.PythonInterpreter
import com.cloudera.livy.repl.scalaRepl.SparkInterpreter
import com.cloudera.livy.repl.sparkr.SparkRInterpreter
import com.cloudera.livy.sessions.SessionState

// scalastyle:off println
object Main extends Logging {
  val SESSION_KIND = "livy.repl.session.kind"
  val CALLBACK_URL = "livy.repl.callbackUrl"
  val PYSPARK_SESSION = "pyspark"
  val SPARK_SESSION = "spark"
  val SPARKR_SESSION = "sparkr"

  def main(args: Array[String]): Unit = {

    val host = sys.props.getOrElse("spark.livy.host", "0.0.0.0")
    val port = sys.props.getOrElse("spark.livy.port", "8999").toInt
    val callbackUrl = sys.props.get("spark.livy.callbackUrl")

    if (args.length != 1) {
      println("Must specify either `pyspark`/`spark`/`sparkr` for the session kind")
      sys.exit(1)
    }

    val session_kind = args.head

    session_kind match {
      case PYSPARK_SESSION | SPARK_SESSION | SPARKR_SESSION =>
      case _ =>
        println("Unknown session kind: " + session_kind)
        sys.exit(1)
    }

    val server = new WebServer(new LivyConf(), host, port)

    server.context.setResourceBase("src/main/com/cloudera/livy/repl")
    server.context.addEventListener(new ScalatraListener)
    server.context.setInitParameter(ScalatraListener.LifeCycleKey,
      classOf[ScalatraBootstrap].getCanonicalName)
    server.context.setInitParameter(SESSION_KIND, session_kind)
    callbackUrl.foreach(server.context.setInitParameter(CALLBACK_URL, _))

    try {
      server.start()

      val replUrl = s"http://${server.host}:${server.port}"
      System.setProperty("livy.repl.url", replUrl)

      println(s"Starting livy-repl on $replUrl")
      Console.flush()

      server.join()
    } finally {
      server.stop()
    }
  }
}

class ScalatraBootstrap extends LifeCycle with Logging {

  protected implicit def executor: ExecutionContext = ExecutionContext.global
  protected implicit def jsonFormats: Formats = DefaultFormats

  var session: Session = null

  override def init(context: ServletContext): Unit = {
    try {
      val interpreter = context.getInitParameter(Main.SESSION_KIND) match {
        case Main.PYSPARK_SESSION => PythonInterpreter()
        case Main.SPARK_SESSION => SparkInterpreter()
        case Main.SPARKR_SESSION => SparkRInterpreter()
      }

      session = Session(interpreter)

      context.mount(new WebApp(session), "/*")

      // See if we want to notify someone that we've started on a url
      Option(context.getInitParameter(Main.CALLBACK_URL)).foreach(notifyCallback)
    } catch {
      case e: Throwable =>
        println(f"Exception thrown when initializing server: $e")
        sys.exit(1)
    }
  }

  override def destroy(context: ServletContext): Unit = {
    if (session != null) {
      session.close()
    }
  }

  private def notifyCallback(callbackUrl: String): Unit = {
    info(s"Notifying $callbackUrl that we're up")

    Future {
      try {
        session.waitForStateChange(SessionState.Starting(), Duration(1, TimeUnit.MINUTES))

        // Wait for our url to be discovered.
        val replUrl = waitForReplUrl()
        info(s"Calling $callbackUrl...")
        val response = Utils.usingResource(new AsyncHttpClient()) { client =>
            client.preparePost(callbackUrl)
            .setHeader("Content-Type", "application/json;charset=UTF-8")
            .setBody(write(Map("url" -> replUrl)))
            .execute().get()
        }
        response.getStatusCode match {
          case HttpServletResponse.SC_OK | HttpServletResponse.SC_ACCEPTED => Future.successful(())
          case statusCode =>
            info("callback fail, " + response.getResponseBody)
            System.exit(1)
        }
      } catch {
        case e: Throwable =>
          error("Exception is thrown in notifyCallback()", e)
          System.exit(1)
      }
    }
  }

  /** Spin until The server may start up  */
  @tailrec
  private def waitForReplUrl(): String = {
    val replUrl = System.getProperty("livy.repl.url")
    if (replUrl == null) {
      Thread.sleep(10)
      waitForReplUrl()
    } else {
      replUrl
    }
  }
}
// scalastyle:on println
