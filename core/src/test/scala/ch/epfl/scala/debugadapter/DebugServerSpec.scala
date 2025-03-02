package ch.epfl.scala.debugadapter

import ch.epfl.scala.debugadapter.internal.DebugSession
import ch.epfl.scala.debugadapter.testing.TestDebugClient
import com.microsoft.java.debug.core.protocol.Events.OutputEvent.Category
import utest._

import java.net.{ConnectException, SocketException, SocketTimeoutException}
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}
import java.util.concurrent.Executors
import java.util.concurrent.TimeoutException

object DebugServerSpec extends TestSuite {
  // the server needs only one thread for delayed responses of the launch and configurationDone requests
  val executorService = Executors.newFixedThreadPool(1)
  implicit val ec = ExecutionContext.fromExecutorService(executorService)

  private val connectionFailedMessages = Array(
    "(Connection refused)",
    "(Connection Failed)",
    "(connect failed)",
    "Connection reset by peer"
  )

  def tests: Tests = Tests {
    "should prevent connection when closed" - {
      val runner = new MockDebuggeeRunner()
      val server = DebugServer(runner, NoopLogger, gracePeriod = Duration.Zero)
      server.close()
      try {
        TestDebugClient.connect(server.uri)
        assert(false) // connect was supposed to fail
      } catch {
        case _: SocketTimeoutException => ()
        case _: ConnectException => ()
        case e: SocketException =>
          val msg = e.getMessage
          assert(connectionFailedMessages.exists(msg.endsWith))
      }
    }

    "should start session when client connects" - {
      val runner = new MockDebuggeeRunner()
      val server = DebugServer(runner, NoopLogger, gracePeriod = Duration.Zero)
      val client = TestDebugClient.connect(server.uri)
      try {
        val session = server.connect()
        assertMatch(session.currentState) { case DebugSession.Started(_) =>
          ()
        }
      } finally {
        server.close()
        client.close()
      }
    }

    "should stop session when closed" - {
      val runner = new MockDebuggeeRunner()
      val server = DebugServer(runner, NoopLogger, gracePeriod = Duration.Zero)
      val client = TestDebugClient.connect(server.uri)
      try {
        val session = server.connect()
        server.close()
        assert(session.currentState == DebugSession.Stopped)
      } finally {
        server.close()
        client.close()
      }
    }

    "should initialize only one connection" - {
      val runner = new MockDebuggeeRunner()
      val server = DebugServer(runner, NoopLogger, gracePeriod = Duration.Zero)
      val client1 = TestDebugClient.connect(server.uri)
      try {
        server.connect()
        client1.initialize()

        var client2: TestDebugClient = null
        try {
          client2 = TestDebugClient.connect(server.uri)
          client2.initialize(1.second)
          assert(false) // the server should not respond to the second client
        } catch {
          case _: SocketTimeoutException => ()
          case _: TimeoutException => ()
        } finally {
          if (client2 != null) client2.close()
        }

      } finally {
        server.close()
        client1.close()
      }
    }

    "should not launch if the debuggee has not started a jvm" - {
      val runner = new MockDebuggeeRunner()
      val server = DebugServer(runner, NoopLogger, gracePeriod = Duration.Zero)
      val client = TestDebugClient.connect(server.uri)
      try {
        server.connect()
        client.initialize()
        assert(!client.launch().success)
      } finally {
        server.close()
        client.close()
      }
    }

    "should set debug address when debuggee starts the jvm" - {
      val runner = MainDebuggeeRunner.sleep()
      val server = DebugServer(runner, NoopLogger)
      val client = TestDebugClient.connect(server.uri)
      try {
        val session = server.connect()
        Await.result(session.getDebugeeAddress, 2.seconds)
      } finally {
        server.close()
        client.close()
      }
    }

    "should respond failure to launch request if debugee throws an exception" - {
      val runner = new MockDebuggeeRunner()
      val server = DebugServer(runner, NoopLogger, gracePeriod = Duration.Zero)
      val client = TestDebugClient.connect(server.uri)
      try {
        server.connect()
        runner.currentProcess.stopped.failure(new Exception())
        client.initialize()
        assert(!client.launch().success)
      } finally {
        server.close()
        client.close()
      }
    }

    "should launch and send initialized event if the debuggee has started a jvm" - {
      val runner = MainDebuggeeRunner.sleep()
      val server = DebugServer(runner, NoopLogger)
      val client = TestDebugClient.connect(server.uri)
      try {
        server.connect()
        client.initialize()
        assert(client.launch().success)
        client.initialized()
        client.configurationDone()
      } finally {
        server.close()
        client.close()
      }
    }

    "should send terminated events when closed" - {
      val runner = MainDebuggeeRunner.sleep()
      val server = DebugServer(runner, NoopLogger)
      val client = TestDebugClient.connect(server.uri)
      try {
        server.connect()
        client.initialize()
        client.launch()
        client.configurationDone()
        // give some time for the debuggee to terminate gracefully
        server.close()
        client.terminated()
      } finally {
        server.close() // in case test fails
        client.close()
      }
    }

    "should send output event when debuggee prints to stdout" - {
      val runner = MainDebuggeeRunner.helloWorld()
      val server = DebugServer(runner, NoopLogger)
      val client = TestDebugClient.connect(server.uri)
      try {
        server.connect()
        client.initialize()
        client.launch()
        client.configurationDone()
        val outputEvent = client.outputed(_.category == Category.stdout)
        assert(outputEvent.output == s"Hello, World!${System.lineSeparator}")
      } finally {
        server.close()
        client.close()
      }
    }

    "should send exited and terminated events when debuggee exits successfully" - {
      val runner = MainDebuggeeRunner.helloWorld()
      val server = DebugServer(runner, NoopLogger)
      val client = TestDebugClient.connect(server.uri)
      try {
        server.connect()
        client.initialize()
        client.launch()
        client.configurationDone()
        // the debuggee should exits immediately
        val exitedEvent = client.exited()
        assert(exitedEvent.exitCode == 0)

        client.terminated()
      } finally {
        server.close()
        client.close()
      }
    }

    "should send exited and terminated events when debuggee exits in error" - {
      val runner = MainDebuggeeRunner.sysExit()
      val server = DebugServer(runner, NoopLogger)
      val client = TestDebugClient.connect(server.uri)
      try {
        server.connect()
        client.initialize()
        client.launch()
        client.configurationDone()
        // the debuggee should exits immediately
        val exitedEvent = client.exited()

        // surprisingly, the java debug implementation always set exitCode to 0
        // https://github.com/microsoft/java-debug/blob/211c47aabec6d47d8393ec39b6fdf0cbfcd8dbb0/com.microsoft.java.debug.core/src/main/java/com/microsoft/java/debug/core/adapter/handler/ConfigurationDoneRequestHandler.java#L84
        assert(exitedEvent.exitCode == 0)

        client.terminated()
      } finally {
        server.close()
        client.close()
      }
    }

    "should support breakpoints in java classes" - {
      val runner = MainDebuggeeRunner.javaBreakpointTest()
      val server = DebugServer(runner, NoopLogger)
      val client = TestDebugClient.connect(server.uri)
      try {
        server.connect()
        client.initialize()
        client.launch()
        val breakpoints =
          client.setBreakpoints(runner.source, Array(5, 12, 16, 8))
        assert(breakpoints.size == 4)
        assert(breakpoints.forall(_.verified))

        client.configurationDone()
        val stopped1 = client.stopped()
        val threadId = stopped1.threadId
        assert(stopped1.reason == "breakpoint")

        client.continue(threadId)
        val stopped2 = client.stopped()
        assert(stopped2.reason == "breakpoint")
        assert(stopped2.threadId == threadId)

        client.continue(threadId)
        val stopped3 = client.stopped()
        assert(stopped3.reason == "breakpoint")
        assert(stopped3.threadId == threadId)

        client.continue(threadId)
        val stopped4 = client.stopped()
        assert(stopped4.reason == "breakpoint")
        assert(stopped4.threadId == threadId)

        client.continue(threadId)
        client.exited()
        client.terminated()
      } finally {
        server.close()
        client.close()
      }
    }

    "should cancel debuggee after receiving disconnect request" - {
      val runner = new MockDebuggeeRunner()
      val server = DebugServer(runner, NoopLogger)
      val client = TestDebugClient.connect(server.uri)

      try {
        val session = server.connect()
        client.initialize()
        client.disconnect(restart = false)

        Await.result(runner.currentProcess.future, 2.seconds)
      } finally {
        server.close()
        client.close()
      }
    }

    "should accept a second connection when the session disconnects with restart = true" - {
      val runner = new MockDebuggeeRunner()
      val handler =
        DebugServer.start(runner, NoopLogger, gracePeriod = Duration.Zero)
      val client1 = TestDebugClient.connect(handler.uri)

      try {
        client1.initialize()
        client1.disconnect(restart = true)

        val client2 = TestDebugClient.connect(handler.uri)
        try {
          client2.initialize()
        } finally {
          client2.close()
        }

      } finally {
        client1.close()
      }
    }

    "should not accept a second connection when the session disconnects with restart = false" - {
      val runner = new MockDebuggeeRunner()
      val handler =
        DebugServer.start(runner, NoopLogger, gracePeriod = Duration.Zero)
      val client1 = TestDebugClient.connect(handler.uri)

      try {
        client1.initialize()
        client1.disconnect(restart = false)

        var client2: TestDebugClient = null
        try {
          client2 = TestDebugClient.connect(handler.uri)
          client2.initialize(1.second)
          assert(false) // it should not accept a second connection
        } catch {
          case _: TimeoutException => ()
          case _: ConnectException => ()
          case _: SocketTimeoutException => ()
          case e: SocketException =>
            val msg = e.getMessage
            assert(connectionFailedMessages.exists(msg.endsWith))
        } finally {
          if (client2 != null) client2.close()
        }

      } finally {
        client1.close()
      }
    }
  }
}
